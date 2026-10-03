package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 温度预警（V5）：按采样点（批次 × 探头）连续超限扫描产生，只追加、不更新、不删除。
 * 预警一旦产生，预警模板变更或重新扫描都不会让它消失；
 * dedup_key 为“批次|采样点|方向|连续段起点”的确定键，数据库唯一约束兜底，
 * 定时任务重启/并发重复扫描只跳过、不多写。
 */
@TableName("t_cc_temp_warning")
public class TempWarning extends CreateOnlyEntity {
    private Long batchId;
    private Long probeId;
    /** TEMP_HIGH 超上限 / TEMP_LOW 低下限 */
    private String warningType;
    private BigDecimal thresholdMinSnap;
    private BigDecimal thresholdMaxSnap;
    private Integer bufferMinutesSnap;
    private Integer jitterGapMinutesSnap;
    private Long templateVersionId;
    private Integer templateVersionNo;
    /** 合并抖动后连续超限段的首个越限采样时间 */
    private LocalDateTime startTime;
    /** 使连续超限时长达到缓冲阈值的采样时间 */
    private LocalDateTime endTime;
    /** start_time -> end_time 的连续超限分钟数（中间短暂正常缺口已按抖动规则合并） */
    private Long durationMinutes;
    private BigDecimal startTemperature;
    private BigDecimal endTemperature;
    private String description;
    /** 去重键：batch|probe|type|startTime */
    private String dedupKey;
    /** 操作者账号快照（定时扫描=system，手工触发=登录管理员），与 create_by 对应 */
    private String operator;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getProbeId() { return probeId; }
    public void setProbeId(Long probeId) { this.probeId = probeId; }
    public String getWarningType() { return warningType; }
    public void setWarningType(String warningType) { this.warningType = warningType; }
    public BigDecimal getThresholdMinSnap() { return thresholdMinSnap; }
    public void setThresholdMinSnap(BigDecimal thresholdMinSnap) { this.thresholdMinSnap = thresholdMinSnap; }
    public BigDecimal getThresholdMaxSnap() { return thresholdMaxSnap; }
    public void setThresholdMaxSnap(BigDecimal thresholdMaxSnap) { this.thresholdMaxSnap = thresholdMaxSnap; }
    public Integer getBufferMinutesSnap() { return bufferMinutesSnap; }
    public void setBufferMinutesSnap(Integer bufferMinutesSnap) { this.bufferMinutesSnap = bufferMinutesSnap; }
    public Integer getJitterGapMinutesSnap() { return jitterGapMinutesSnap; }
    public void setJitterGapMinutesSnap(Integer jitterGapMinutesSnap) { this.jitterGapMinutesSnap = jitterGapMinutesSnap; }
    public Long getTemplateVersionId() { return templateVersionId; }
    public void setTemplateVersionId(Long templateVersionId) { this.templateVersionId = templateVersionId; }
    public Integer getTemplateVersionNo() { return templateVersionNo; }
    public void setTemplateVersionNo(Integer templateVersionNo) { this.templateVersionNo = templateVersionNo; }
    public LocalDateTime getStartTime() { return startTime; }
    public void setStartTime(LocalDateTime startTime) { this.startTime = startTime; }
    public LocalDateTime getEndTime() { return endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public Long getDurationMinutes() { return durationMinutes; }
    public void setDurationMinutes(Long durationMinutes) { this.durationMinutes = durationMinutes; }
    public BigDecimal getStartTemperature() { return startTemperature; }
    public void setStartTemperature(BigDecimal startTemperature) { this.startTemperature = startTemperature; }
    public BigDecimal getEndTemperature() { return endTemperature; }
    public void setEndTemperature(BigDecimal endTemperature) { this.endTemperature = endTemperature; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getDedupKey() { return dedupKey; }
    public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }
}
