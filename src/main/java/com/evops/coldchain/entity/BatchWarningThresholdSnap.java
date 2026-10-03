package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.CreateOnlyEntity;

import java.math.BigDecimal;

/**
 * 批次预警阈值快照（派车时固化，只追加、不可修改）。
 * 预警模板后续维护只影响此后新建批次；旧批次的扫描、判定、追溯一律以本快照为准。
 * inheritanceDetail 逐字段记录阈值来源（本类覆盖 / 继承自哪个祖先类别 V几）。
 */
@TableName("t_cc_batch_warning_threshold_snap")
public class BatchWarningThresholdSnap extends CreateOnlyEntity {
    private Long batchId;
    private Long categoryId;
    private String categoryNoSnap;
    private String categoryNameSnap;
    private String categoryPathSnap;
    private Long templateVersionId;
    private Integer templateVersionNo;
    /** 沿继承链解析（本类覆盖优先、逐级回退父类）后的最终阈值 */
    private BigDecimal tempMinSnap;
    private BigDecimal tempMaxSnap;
    private Integer warnBufferMinutesSnap;
    private Integer jitterGapMinutesSnap;
    /** 逐字段来源说明 */
    private String inheritanceDetail;

    public Long getBatchId() { return batchId; }
    public void setBatchId(Long batchId) { this.batchId = batchId; }
    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public String getCategoryNoSnap() { return categoryNoSnap; }
    public void setCategoryNoSnap(String categoryNoSnap) { this.categoryNoSnap = categoryNoSnap; }
    public String getCategoryNameSnap() { return categoryNameSnap; }
    public void setCategoryNameSnap(String categoryNameSnap) { this.categoryNameSnap = categoryNameSnap; }
    public String getCategoryPathSnap() { return categoryPathSnap; }
    public void setCategoryPathSnap(String categoryPathSnap) { this.categoryPathSnap = categoryPathSnap; }
    public Long getTemplateVersionId() { return templateVersionId; }
    public void setTemplateVersionId(Long templateVersionId) { this.templateVersionId = templateVersionId; }
    public Integer getTemplateVersionNo() { return templateVersionNo; }
    public void setTemplateVersionNo(Integer templateVersionNo) { this.templateVersionNo = templateVersionNo; }
    public BigDecimal getTempMinSnap() { return tempMinSnap; }
    public void setTempMinSnap(BigDecimal tempMinSnap) { this.tempMinSnap = tempMinSnap; }
    public BigDecimal getTempMaxSnap() { return tempMaxSnap; }
    public void setTempMaxSnap(BigDecimal tempMaxSnap) { this.tempMaxSnap = tempMaxSnap; }
    public Integer getWarnBufferMinutesSnap() { return warnBufferMinutesSnap; }
    public void setWarnBufferMinutesSnap(Integer warnBufferMinutesSnap) { this.warnBufferMinutesSnap = warnBufferMinutesSnap; }
    public Integer getJitterGapMinutesSnap() { return jitterGapMinutesSnap; }
    public void setJitterGapMinutesSnap(Integer jitterGapMinutesSnap) { this.jitterGapMinutesSnap = jitterGapMinutesSnap; }
    public String getInheritanceDetail() { return inheritanceDetail; }
    public void setInheritanceDetail(String inheritanceDetail) { this.inheritanceDetail = inheritanceDetail; }
}
