package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.entity.BatchWarningThresholdSnap;
import com.evops.coldchain.entity.TemperatureLog;
import com.evops.coldchain.entity.TempWarning;
import com.evops.coldchain.mapper.BatchWarningThresholdSnapMapper;
import com.evops.coldchain.mapper.TemperatureLogMapper;
import com.evops.coldchain.mapper.TempWarningMapper;
import com.evops.common.UserContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 温度预警扫描（V5）。
 *
 * 判定口径（全部以批次创建时固化的阈值快照为准，模板变更不影响既有批次，也不会删除已产生的预警）：
 * 1. 按“采样点（批次 × 探头）”取全部温度留痕，沿时间重放。
 * 2. 单采样点上把采样分为 超上限/低下限/正常；同方向的连续超限归为一段。
 * 3. 抖动合并：同方向两次超限之间夹着的短暂回温（回正常）缺口不超过快照 jitterGapMinutes 时，
 *    不打断连续计时，前后两段合并为同一段；缺口超过抖动窗口才切断，另起新段。
 * 4. 合并后连续超限时长（段起点采样 -> 当前超限采样）达到 warnBufferMinutes 即产生一条预警；
 *    同一段只产生一条，后续超限不再补第二条。
 * 5. 预警只追加；dedup_key=批次|采样点|方向|段起点，由唯一约束兜底——
 *    定时任务重启或并发重复扫描时，已存在的预警跳过，不多写、不覆盖、不删除。
 */
@Service
public class AlertScanService {

    private static final Logger log = LoggerFactory.getLogger(AlertScanService.class);
    private static final DateTimeFormatter KEY_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Resource
    private BatchWarningThresholdSnapMapper snapMapper;
    @Resource
    private TemperatureLogMapper temperatureMapper;
    @Resource
    private TempWarningMapper warningMapper;
    @Resource
    private TempWarningWriter warningWriter;
    @Resource
    private OpLogService opLogService;

    /** 扫描结果：扫描批次数、新增预警数、因重启/并发去重跳过数 */
    public static class ScanResult {
        public int scannedBatches;
        public int inserted;
        public int duplicated;

        public ScanResult(int scannedBatches, int inserted, int duplicated) {
            this.scannedBatches = scannedBatches;
            this.inserted = inserted;
            this.duplicated = duplicated;
        }
    }

    /**
     * 全量扫描所有持有类别阈值快照的批次。无快照（V5 之前建档药品的）批次不在类别预警范围内。
     * 整体只追加：任何失败都不会删除/修改既有预警。
     */
    @Transactional
    public ScanResult scanAll() {
        List<BatchWarningThresholdSnap> snaps = snapMapper.selectList(
                new QueryWrapper<BatchWarningThresholdSnap>().orderByAsc("id"));
        int inserted = 0;
        int duplicated = 0;
        for (BatchWarningThresholdSnap snap : snaps) {
            int[] counters = scanBatch(snap);
            inserted += counters[0];
            duplicated += counters[1];
        }
        // 仅在确有新增预警时写流水；纯去重跳过（如定时任务每轮重启重放）不制造日志噪声。
        // 手工触发的完整计数由控制器另写一条 WARNING_SCAN 流水。
        if (inserted > 0) {
            opLogService.log(CcConstants.ENT_WARNING, null, null, CcConstants.ACT_WARNING_SCAN,
                    String.format("温控预警扫描完成：扫描批次=%d，新增预警=%d，重启/并发去重跳过=%d（预警只追加，模板变更不会使已产生预警消失）",
                            snaps.size(), inserted, duplicated));
        } else {
            log.debug("温控预警扫描完成：扫描批次={}，去重跳过={}，无新增预警", snaps.size(), duplicated);
        }
        return new ScanResult(snaps.size(), inserted, duplicated);
    }

    /**
     * 重放单批次温度，按采样点（探头）归组后分别判定。
     * @return [新增条数, 去重跳过条数]
     */
    private int[] scanBatch(BatchWarningThresholdSnap snap) {
        List<TemperatureLog> logs = temperatureMapper.selectList(new QueryWrapper<TemperatureLog>()
                .eq("batch_id", snap.getBatchId())
                .orderByAsc("record_time")
                .orderByAsc("id"));
        if (logs.isEmpty()) {
            return new int[]{0, 0};
        }
        // 采样点归组：一个探头一个连续序列；历史无探头行归到 null 采样点
        Map<Long, List<TemperatureLog>> byProbe = new LinkedHashMap<>();
        for (TemperatureLog t : logs) {
            byProbe.computeIfAbsent(t.getProbeId(), k -> new ArrayList<>()).add(t);
        }

        int inserted = 0;
        int duplicated = 0;
        for (Map.Entry<Long, List<TemperatureLog>> point : byProbe.entrySet()) {
            int[] counters = replayPoint(snap, point.getKey(), point.getValue());
            inserted += counters[0];
            duplicated += counters[1];
        }
        return new int[]{inserted, duplicated};
    }

    /**
     * 单个采样点上的连续超限重放（含抖动合并）。
     */
    private int[] replayPoint(BatchWarningThresholdSnap snap, Long probeId, List<TemperatureLog> logs) {
        final BigDecimal min = snap.getTempMinSnap();
        final BigDecimal max = snap.getTempMaxSnap();
        final int bufferMinutes = snap.getWarnBufferMinutesSnap();
        final int jitterGapMinutes = snap.getJitterGapMinutesSnap();

        int inserted = 0;
        int duplicated = 0;

        // 当前（尚未结束的）同方向连续超限段
        String runDir = null;
        TemperatureLog runStart = null;
        TemperatureLog lastExcursion = null;
        boolean fired = false;
        // 自上次越限采样后是否出现过正常点（短暂回温）：
        // 只有夹过正常点时才谈“抖动缺口”；两次越限间没有任何正常采样（稀疏上报）不切断连续段
        boolean normalSinceLastExcursion = false;

        for (TemperatureLog t : logs) {
            String dir = classify(t.getTemperature(), min, max);
            if (dir == null) {
                normalSinceLastExcursion = true;
                // 正常点是否打断取决于下一次同方向超限与上次超限的间隔，此处不立即切段
                continue;
            }
            if (runDir == null) {
                runDir = dir;
                runStart = t;
                lastExcursion = t;
                fired = false;
            } else if (runDir.equals(dir)) {
                if (normalSinceLastExcursion) {
                    // 中间夹过回温采样：缺口超过抖动合并窗口才切断，未超过则把短暂抖动合并进原段
                    long gapMinutes = Duration.between(lastExcursion.getRecordTime(), t.getRecordTime()).toMinutes();
                    if (gapMinutes > jitterGapMinutes) {
                        runStart = t;
                        fired = false;
                    }
                }
                // 无正常点间隔的稀疏越限采样视为同一段延续，段起点不变
                lastExcursion = t;
            } else {
                // 反方向超限：旧段结束，新方向另起一段
                runDir = dir;
                runStart = t;
                lastExcursion = t;
                fired = false;
            }
            normalSinceLastExcursion = false;

            if (!fired) {
                long duration = Duration.between(runStart.getRecordTime(), t.getRecordTime()).toMinutes();
                if (duration >= bufferMinutes) {
                    boolean created = insertWarning(snap, probeId, runDir, runStart, t, duration);
                    if (created) {
                        inserted++;
                    } else {
                        duplicated++;
                    }
                    fired = true; // 同一段只出一条；重启场景下的既有预警也不重复出
                }
            }
        }
        return new int[]{inserted, duplicated};
    }

    /** 超上限 HIGH / 低下限 LOW / 正常 null（边界值含在温区内，不算越限） */
    private String classify(BigDecimal temperature, BigDecimal min, BigDecimal max) {
        if (temperature.compareTo(max) > 0) {
            return CcConstants.WARNING_HIGH;
        }
        if (temperature.compareTo(min) < 0) {
            return CcConstants.WARNING_LOW;
        }
        return null;
    }

    /**
     * 追加一条预警（每条独立事务提交）；dedup_key 已存在时返回 false 计为去重跳过。
     * 预警从无更新/删除路径。
     */
    private boolean insertWarning(BatchWarningThresholdSnap snap, Long probeId, String type,
                                  TemperatureLog start, TemperatureLog end, long durationMinutes) {
        TempWarning warning = new TempWarning();
        warning.setBatchId(snap.getBatchId());
        warning.setProbeId(probeId);
        warning.setWarningType(type);
        warning.setThresholdMinSnap(snap.getTempMinSnap());
        warning.setThresholdMaxSnap(snap.getTempMaxSnap());
        warning.setBufferMinutesSnap(snap.getWarnBufferMinutesSnap());
        warning.setJitterGapMinutesSnap(snap.getJitterGapMinutesSnap());
        warning.setTemplateVersionId(snap.getTemplateVersionId());
        warning.setTemplateVersionNo(snap.getTemplateVersionNo());
        warning.setStartTime(start.getRecordTime());
        warning.setEndTime(end.getRecordTime());
        warning.setDurationMinutes(durationMinutes);
        warning.setStartTemperature(start.getTemperature());
        warning.setEndTemperature(end.getTemperature());
        warning.setDescription(buildDescription(snap, probeId, type, start, end, durationMinutes));
        warning.setDedupKey(buildDedupKey(snap.getBatchId(), probeId, type, start.getRecordTime()));
        // 操作者快照：定时扫描落到 system（UserContext 无 Shiro 主体时的回退），手工触发为登录管理员
        warning.setOperator(UserContext.currentName());
        // 独立事务：重启/并发重放命中唯一键时只跳过这一条，不多写、不影响其他预警
        boolean created = warningWriter.insertIfAbsent(warning);
        if (!created) {
            log.info("温控预警去重跳过：batch={}, probe={}, type={}, start={}",
                    snap.getBatchId(), probeId, type, start.getRecordTime());
        }
        return created;
    }

    private String buildDescription(BatchWarningThresholdSnap snap, Long probeId, String type,
                                    TemperatureLog start, TemperatureLog end, long durationMinutes) {
        String direction = CcConstants.WARNING_HIGH.equals(type)
                ? String.format("高于预警上限 %s℃", snap.getTempMaxSnap())
                : String.format("低于预警下限 %s℃", snap.getTempMinSnap());
        return String.format(
                "系统扫描预警：类别[%s]批次按创建时阈值快照（模板 V%d，预警温区 %s~%s℃，连续超限缓冲 %d 分钟，抖动合并 %d 分钟）判定，"
                        + "采样点探头=%s 自 %s 起%s（首采样 %s℃），期间短暂回温未超过抖动窗口已合并，"
                        + "至 %s 持续 %d 分钟达到缓冲阈值（触发采样 %s℃）。",
                snap.getCategoryPathSnap(), snap.getTemplateVersionNo(),
                snap.getTempMinSnap(), snap.getTempMaxSnap(),
                snap.getWarnBufferMinutesSnap(), snap.getJitterGapMinutesSnap(),
                probeId == null ? "未绑定（历史批次）" : String.valueOf(probeId),
                start.getRecordTime(), direction, start.getTemperature(),
                end.getRecordTime(), durationMinutes, end.getTemperature());
    }

    /** 去重键：批次|采样点|方向|连续段起点（重放结果确定，重启后重算仍为同一键） */
    private String buildDedupKey(Long batchId, Long probeId, String type, LocalDateTime startTime) {
        return batchId + "|" + (probeId == null ? 0L : probeId) + "|" + type + "|" + startTime.format(KEY_TIME);
    }

    // ---------------- 管理员查询（经 Shiro 角色校验后进入） ----------------

    public List<TempWarning> listAll() {
        return warningMapper.selectList(new QueryWrapper<TempWarning>()
                .orderByDesc("id"));
    }

    public List<TempWarning> listByBatch(Long batchId) {
        return warningMapper.selectList(new QueryWrapper<TempWarning>()
                .eq("batch_id", batchId).orderByAsc("start_time").orderByAsc("id"));
    }

    public long countAll() {
        Long count = warningMapper.selectCount(null);
        return count == null ? 0 : count;
    }
}
