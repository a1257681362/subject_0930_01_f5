package com.evops.coldchain.dto;

import javax.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 类别温控预警模板维护请求（每次维护追加一个新版本）。
 * 四个阈值字段均允许为空：
 * - 留空表示该字段沿父类链继承；填值表示本类在该温区/参数上的覆盖。
 * - 字段级独立继承：可只覆盖温区上限，其余字段继续继承上级。
 */
public class TemplateMaintainRequest {
    @NotNull(message = "类别ID不能为空")
    private Long categoryId;
    /** 预警温区下限℃；null=继承上级 */
    private BigDecimal tempMin;
    /** 预警温区上限℃；null=继承上级 */
    private BigDecimal tempMax;
    /** 连续超限缓冲分钟（持续达到才预警）；null=继承上级 */
    private Integer warnBufferMinutes;
    /** 抖动合并缺口分钟（短暂回正常不超过该值则合并计时）；null=继承上级 */
    private Integer jitterGapMinutes;
    private String changeReason;

    public Long getCategoryId() { return categoryId; }
    public void setCategoryId(Long categoryId) { this.categoryId = categoryId; }
    public BigDecimal getTempMin() { return tempMin; }
    public void setTempMin(BigDecimal tempMin) { this.tempMin = tempMin; }
    public BigDecimal getTempMax() { return tempMax; }
    public void setTempMax(BigDecimal tempMax) { this.tempMax = tempMax; }
    public Integer getWarnBufferMinutes() { return warnBufferMinutes; }
    public void setWarnBufferMinutes(Integer warnBufferMinutes) { this.warnBufferMinutes = warnBufferMinutes; }
    public Integer getJitterGapMinutes() { return jitterGapMinutes; }
    public void setJitterGapMinutes(Integer jitterGapMinutes) { this.jitterGapMinutes = jitterGapMinutes; }
    public String getChangeReason() { return changeReason; }
    public void setChangeReason(String changeReason) { this.changeReason = changeReason; }
}
