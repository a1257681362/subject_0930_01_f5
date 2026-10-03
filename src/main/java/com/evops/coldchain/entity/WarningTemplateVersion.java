package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 类别温控预警模板版本（追加式）。
 * 每次维护生成新版本行并把旧版本置 SUPERSEDED，历史版本原值永不修改。
 * 四个阈值字段允许留空：
 * - 温区下限/上限留空 = 沿父类链继承，填值 = 本类覆盖（字段级独立继承/覆盖）；
 * - 连续超限缓冲分钟、抖动合并缺口分钟同理。
 * 派车时按当时叶子类别的生效版本解析继承链并固化到批次快照，此后与本表脱钩。
 */
@TableName("t_cc_warning_template_version")
public class WarningTemplateVersion extends BaseEntity {
    private Long categoryId;
    private Integer versionNo;
    /** 预警温区下限℃；null=继承上级 */
    private BigDecimal tempMin;
    /** 预警温区上限℃；null=继承上级 */
    private BigDecimal tempMax;
    /** 连续超限缓冲分钟：持续超限达到该时长才出预警；null=继承上级 */
    private Integer warnBufferMinutes;
    /** 抖动合并：两次超限间正常缺口不超过该分钟数则合并计时；null=继承上级 */
    private Integer jitterGapMinutes;
    /** EFFECTIVE / SUPERSEDED / REVOKED */
    private String status;
    private String changeReason;
    private LocalDateTime publishTime;

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public Integer getVersionNo() { return versionNo; }
    public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
    public BigDecimal getTempMin() { return tempMin; }
    public void setTempMin(BigDecimal tempMin) { this.tempMin = tempMin; }
    public BigDecimal getTempMax() { return tempMax; }
    public void setTempMax(BigDecimal tempMax) { this.tempMax = tempMax; }
    public Integer getWarnBufferMinutes() { return warnBufferMinutes; }
    public void setWarnBufferMinutes(Integer warnBufferMinutes) { this.warnBufferMinutes = warnBufferMinutes; }
    public Integer getJitterGapMinutes() { return jitterGapMinutes; }
    public void setJitterGapMinutes(Integer jitterGapMinutes) { this.jitterGapMinutes = jitterGapMinutes; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
    public LocalDateTime getPublishTime() { return publishTime; }
    public void setPublishTime(LocalDateTime publishTime) { this.publishTime = publishTime; }
}
