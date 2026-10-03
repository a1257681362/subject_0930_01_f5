package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.TemplateMaintainRequest;
import com.evops.coldchain.entity.BatchWarningThresholdSnap;
import com.evops.coldchain.entity.DrugCategory;
import com.evops.coldchain.entity.WarningTemplateVersion;
import com.evops.coldchain.mapper.BatchWarningThresholdSnapMapper;
import com.evops.coldchain.mapper.WarningTemplateVersionMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 类别温控预警模板服务（V5）。
 *
 * 继承与覆盖：
 * - 每个类别的模板按版本只追加：维护时旧 EFFECTIVE 版本置 SUPERSEDED，新版本 EFFECTIVE，
 *   历史版本行原值永不修改（与药品档案/路线版本同一口径）。
 * - 阈值为“字段级继承”：新版本四字段（温区下限/上限、连续超限缓冲分钟、抖动合并缺口分钟）
 *   留空即沿父类链向上找最近的非空值，填值即本类覆盖；可只覆盖其中一个字段。
 * - 派车瞬间解析整条链并固化为批次快照（{@link BatchWarningThresholdSnap}）；
 *   模板此后的维护只影响之后新建批次，旧批次扫描/追溯永远用快照。
 */
@Service
public class WarningTemplateService {

    @Resource
    private WarningTemplateVersionMapper versionMapper;
    @Resource
    private BatchWarningThresholdSnapMapper snapMapper;
    @Resource
    private DrugCategoryService categoryService;
    @Resource
    private OpLogService opLogService;

    /** 沿继承链解析后的最终阈值 + 逐字段来源说明 */
    public static class ResolvedThresholds {
        public BigDecimal tempMin;
        public BigDecimal tempMax;
        public Integer warnBufferMinutes;
        public Integer jitterGapMinutes;
        /** 锚点模板版本：链上最近（最深）的生效模板版本 */
        public WarningTemplateVersion anchorVersion;
        /** 逐字段来源（温区下限/上限/缓冲/抖动各一条） */
        public String inheritanceDetail;
    }

    /**
     * 维护模板：校验本类入参与父链继承结果后，旧生效版本置 SUPERSEDED 并追加新版本。
     * 只改“当前版本指针”，任何历史版本行原值不动。
     */
    @Transactional
    public WarningTemplateVersion maintain(TemplateMaintainRequest req) {
        DrugCategory category = categoryService.requireActiveCategory(req.getCategoryId());
        validateInputs(req);

        // 新版本留空的字段必须能从父类链（不含本类——本类旧版本即将被封存）继承到，
        // 否则维护完成后该类别阈值解析不完整，此处直接给出停点与缺失字段。
        resolveWithCandidate(category, req);

        WarningTemplateVersion current = findEffectiveVersion(category.getId());
        int nextVersionNo = current == null ? 1 : current.getVersionNo() + 1;
        if (current != null) {
            current.setStatus(CcConstants.SUPERSEDED);
            versionMapper.updateById(current);
        }

        WarningTemplateVersion version = new WarningTemplateVersion();
        version.setCategoryId(category.getId());
        version.setVersionNo(nextVersionNo);
        version.setTempMin(req.getTempMin());
        version.setTempMax(req.getTempMax());
        version.setWarnBufferMinutes(req.getWarnBufferMinutes());
        version.setJitterGapMinutes(req.getJitterGapMinutes());
        version.setStatus(CcConstants.EFFECTIVE);
        version.setChangeReason(req.getChangeReason());
        version.setPublishTime(LocalDateTime.now());
        versionMapper.insert(version);

        // 新版本入库后再解析一次，得到逐字段“本类覆盖/继承自上级 V几”的完整来源说明
        ResolvedThresholds resolvedAfter = resolveForDispatch(category.getId());
        opLogService.log(CcConstants.ENT_WARNING_TEMPLATE, version.getId(), null,
                CcConstants.ACT_TEMPLATE_MAINTAIN,
                String.format("类别[%s]温控预警模板维护至 V%d（%s）：%s；历史版本封存，模板变更仅影响此后新建批次",
                        categoryService.categoryPath(category.getId()), nextVersionNo,
                        current == null ? "首个版本" : "替换 V" + current.getVersionNo(),
                        resolvedAfter.inheritanceDetail));
        return version;
    }

    private void validateInputs(TemplateMaintainRequest req) {
        if (req.getTempMin() != null && req.getTempMax() != null
                && req.getTempMin().compareTo(req.getTempMax()) > 0) {
            throw new BusinessException("预警温区下限不能高于上限");
        }
        if (req.getWarnBufferMinutes() != null && req.getWarnBufferMinutes() < 0) {
            throw new BusinessException("连续超限缓冲分钟不能为负");
        }
        if (req.getJitterGapMinutes() != null && req.getJitterGapMinutes() < 0) {
            throw new BusinessException("抖动合并缺口分钟不能为负");
        }
    }

    /**
     * 按“候选新版本（本类覆盖值）+ 父类链继承”解析最终阈值，仅用于维护时校验。
     * 本类旧生效版本不参与（新版本发布后它会被封存）。
     */
    private ResolvedThresholds resolveWithCandidate(DrugCategory leaf, TemplateMaintainRequest candidate) {
        List<DrugCategory> chain = categoryService.inheritanceChain(leaf.getId());
        List<WarningTemplateVersion> ancestorVersions = new ArrayList<>();
        for (int i = 1; i < chain.size(); i++) { // 跳过叶子自身
            WarningTemplateVersion v = findEffectiveVersion(chain.get(i).getId());
            if (v != null) {
                ancestorVersions.add(v);
            }
        }
        ResolvedThresholds r = new ResolvedThresholds();
        r.tempMin = firstNonNull(candidate.getTempMin(), ancestorVersions, "tempMin");
        r.tempMax = firstNonNull(candidate.getTempMax(), ancestorVersions, "tempMax");
        r.warnBufferMinutes = firstNonNull(candidate.getWarnBufferMinutes(), ancestorVersions, "warnBufferMinutes");
        r.jitterGapMinutes = firstNonNull(candidate.getJitterGapMinutes(), ancestorVersions, "jitterGapMinutes");
        r.anchorVersion = ancestorVersions.isEmpty() ? null : ancestorVersions.get(0);
        List<String> missing = new ArrayList<>();
        if (r.tempMin == null) { missing.add("预警温区下限"); }
        if (r.tempMax == null) { missing.add("预警温区上限"); }
        if (r.warnBufferMinutes == null) { missing.add("连续超限缓冲分钟"); }
        if (r.jitterGapMinutes == null) { missing.add("抖动合并缺口分钟"); }
        if (!missing.isEmpty()) {
            throw new BusinessException(String.format(
                    "类别[%s]的父类链未配置以下阈值，且本次维护未覆盖：%s；请在本类填值或先在上级类别维护模板",
                    categoryService.categoryPath(leaf.getId()), String.join("、", missing)));
        }
        if (r.tempMin.compareTo(r.tempMax) > 0) {
            throw new BusinessException(String.format(
                    "继承合并后预警温区倒置（下限 %s℃ > 上限 %s℃），请调整本类覆盖值或上级模板",
                    r.tempMin, r.tempMax));
        }
        return r;
    }

    private <T> T firstNonNull(T ownValue, List<WarningTemplateVersion> ancestors, String field) {
        if (ownValue != null) {
            return ownValue;
        }
        for (WarningTemplateVersion v : ancestors) {
            T value = readField(v, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private <T> T readField(WarningTemplateVersion v, String field) {
        switch (field) {
            case "tempMin": return (T) v.getTempMin();
            case "tempMax": return (T) v.getTempMax();
            case "warnBufferMinutes": return (T) v.getWarnBufferMinutes();
            case "jitterGapMinutes": return (T) v.getJitterGapMinutes();
            default: throw new IllegalArgumentException(field);
        }
    }

    /**
     * 派车时按叶子类别当前生效模板解析整条继承链。
     * 链上任何类别都没有生效模板时抛业务停点（类别已挂药品却完全未配置模板=配置缺失）。
     */
    public ResolvedThresholds resolveForDispatch(Long leafCategoryId) {
        List<DrugCategory> chain = categoryService.inheritanceChain(leafCategoryId);
        List<WarningTemplateVersion> chainVersions = new ArrayList<>();
        for (DrugCategory c : chain) {
            WarningTemplateVersion v = findEffectiveVersion(c.getId());
            if (v != null) {
                chainVersions.add(v);
            }
        }
        if (chainVersions.isEmpty()) {
            throw new BusinessException(String.format(
                    "药品类别[%s]及其上级类别均未维护温控预警模板，无法为新批次固化阈值快照；请先维护模板",
                    categoryService.categoryPath(leafCategoryId)));
        }
        ResolvedThresholds r = new ResolvedThresholds();
        StringBuilder detail = new StringBuilder();
        r.anchorVersion = chainVersions.get(0); // 链上最近的生效模板
        r.tempMin = resolveFieldWithSource(chain, chainVersions, "tempMin", "预警温区下限", detail);
        r.tempMax = resolveFieldWithSource(chain, chainVersions, "tempMax", "预警温区上限", detail);
        r.warnBufferMinutes = resolveFieldWithSource(chain, chainVersions,
                "warnBufferMinutes", "连续超限缓冲分钟", detail);
        r.jitterGapMinutes = resolveFieldWithSource(chain, chainVersions,
                "jitterGapMinutes", "抖动合并缺口分钟", detail);
        r.inheritanceDetail = detail.toString();
        if (r.tempMin.compareTo(r.tempMax) > 0) {
            throw new BusinessException(String.format(
                    "类别[%s]继承合并后预警温区倒置（下限 %s℃ > 上限 %s℃），请修正模板",
                    categoryService.categoryPath(leafCategoryId), r.tempMin, r.tempMax));
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private <T> T resolveFieldWithSource(List<DrugCategory> chain, List<WarningTemplateVersion> versions,
                                         String field, String fieldLabel, StringBuilder detail) {
        for (int i = 0; i < chain.size(); i++) {
            WarningTemplateVersion v = findEffectiveVersion(chain.get(i).getId());
            if (v == null) {
                continue;
            }
            T value;
            switch (field) {
                case "tempMin": value = (T) v.getTempMin(); break;
                case "tempMax": value = (T) v.getTempMax(); break;
                case "warnBufferMinutes": value = (T) v.getWarnBufferMinutes(); break;
                case "jitterGapMinutes": value = (T) v.getJitterGapMinutes(); break;
                default: throw new IllegalArgumentException(field);
            }
            if (value != null) {
                if (detail.length() > 0) {
                    detail.append("；");
                }
                detail.append(String.format("%s=%s（%s V%d%s）", fieldLabel, value,
                        chain.get(i).getCategoryName(), v.getVersionNo(),
                        i == 0 ? "覆盖" : "继承"));
                return value;
            }
        }
        return null;
    }

    /** 派车时固化批次阈值快照（每批至多一行）；药品无类别的批次返回 null，不走类别预警 */
    @Transactional
    public BatchWarningThresholdSnap snapshotForBatch(Long batchId, Long leafCategoryId) {
        if (leafCategoryId == null) {
            return null;
        }
        BatchWarningThresholdSnap existing = getSnapshotByBatch(batchId);
        if (existing != null) {
            return existing; // 幂等保护：快照只在批次创建时固化一次
        }
        DrugCategory leaf = categoryService.requireCategory(leafCategoryId);
        ResolvedThresholds r = resolveForDispatch(leafCategoryId);

        BatchWarningThresholdSnap snap = new BatchWarningThresholdSnap();
        snap.setBatchId(batchId);
        snap.setCategoryId(leaf.getId());
        snap.setCategoryNoSnap(leaf.getCategoryNo());
        snap.setCategoryNameSnap(leaf.getCategoryName());
        snap.setCategoryPathSnap(categoryService.categoryPath(leafCategoryId));
        snap.setTemplateVersionId(r.anchorVersion.getId());
        snap.setTemplateVersionNo(r.anchorVersion.getVersionNo());
        snap.setTempMinSnap(r.tempMin);
        snap.setTempMaxSnap(r.tempMax);
        snap.setWarnBufferMinutesSnap(r.warnBufferMinutes);
        snap.setJitterGapMinutesSnap(r.jitterGapMinutes);
        snap.setInheritanceDetail(r.inheritanceDetail);
        snapMapper.insert(snap);
        return snap;
    }

    public BatchWarningThresholdSnap getSnapshotByBatch(Long batchId) {
        List<BatchWarningThresholdSnap> rows = snapMapper.selectList(
                new QueryWrapper<BatchWarningThresholdSnap>().eq("batch_id", batchId));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public WarningTemplateVersion findEffectiveVersion(Long categoryId) {
        List<WarningTemplateVersion> rows = versionMapper.selectList(new QueryWrapper<WarningTemplateVersion>()
                .eq("category_id", categoryId)
                .eq("status", CcConstants.EFFECTIVE)
                .orderByDesc("version_no")
                .last("LIMIT 1"));
        return rows.isEmpty() ? null : rows.get(0);
    }

    public List<WarningTemplateVersion> listVersions(Long categoryId) {
        return versionMapper.selectList(new QueryWrapper<WarningTemplateVersion>()
                .eq("category_id", categoryId).orderByDesc("version_no"));
    }
}
