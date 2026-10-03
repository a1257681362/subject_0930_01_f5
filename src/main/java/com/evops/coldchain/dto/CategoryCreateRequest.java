package com.evops.coldchain.dto;

import javax.validation.constraints.NotBlank;

/** 药品类别建档请求（可挂在父类别下形成类别树） */
public class CategoryCreateRequest {
    @NotBlank(message = "类别名称不能为空")
    private String categoryName;
    /** 父类别ID；不填为根类别 */
    private Long parentId;
    private String remark;

    public String getCategoryName() { return categoryName; }
    public void setCategoryName(String categoryName) { this.categoryName = categoryName; }
    public Long getParentId() { return parentId; }
    public void setParentId(Long parentId) { this.parentId = parentId; }
    public String getRemark() { return remark; }
    public void setRemark(String remark) { this.remark = remark; }
}
