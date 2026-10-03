package com.evops.coldchain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.evops.common.BaseEntity;

/**
 * 药品类别树（V5）。
 * parent_id 为空表示根类别；子类的预警模板阈值字段留空时沿父链逐级继承。
 * 类别只停用（REVOKED）不删除：已建批次的阈值快照仍可追溯到当时的类别。
 */
@TableName("t_cc_drug_category")
public class DrugCategory extends BaseEntity {
    private String categoryNo;
    private String categoryName;
    /** 父类别ID，空=根类别 */
    private Long parentId;
    /** 层级（根=1），用于防止误挂形成环 */
    private Integer levelNo;
    /** ACTIVE / REVOKED */
    private String status;
    private String remark;

    public String getCategoryNo() { return categoryNo; }
    public void setCategoryNo(String categoryNo) { this.categoryNo = categoryNo; }
    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public Integer getLevelNo() { return levelNo; }
    public void setLevelNo(Integer levelNo) { this.levelNo = levelNo; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
