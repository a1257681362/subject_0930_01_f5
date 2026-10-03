package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.CcConstants;
import com.evops.coldchain.dto.CategoryCreateRequest;
import com.evops.coldchain.entity.DrugCategory;
import com.evops.coldchain.mapper.DrugCategoryMapper;
import com.evops.coldchain.mapper.SequenceMapper;
import com.evops.common.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 药品类别服务（V5）。
 * 类别构成自引用树：子类别的预警阈值字段留空时沿 parent 链逐级继承。
 * 类别只停用（REVOKED）不删除——停用后不可新建药品/维护模板，但既有批次阈值快照
 * 与子类继承解析仍可引用其历史模板版本。
 */
@Service
public class DrugCategoryService {

    @Resource
    private DrugCategoryMapper categoryMapper;
    @Resource
    private SequenceMapper sequenceMapper;
    @Resource
    private OpLogService opLogService;

    @Transactional
    public DrugCategory create(CategoryCreateRequest req) {
        DrugCategory category = new DrugCategory();
        category.setCategoryNo("LB" + sequenceMapper.nextCategoryNo());
        category.setCategoryName(req.getCategoryName().trim());
        category.setStatus(CcConstants.ACTIVE);
        category.setRemark(req.getRemark());
        if (req.getParentId() != null) {
            DrugCategory parent = requireActiveCategory(req.getParentId());
            category.setParentId(parent.getId());
            category.setLevelNo(parent.getLevelNo() + 1);
        } else {
            category.setLevelNo(1);
        }
        categoryMapper.insert(category);

        opLogService.log(CcConstants.ENT_CATEGORY, category.getId(), null, CcConstants.ACT_CATEGORY_CREATE,
                String.format("药品类别建档：%s(%s)，层级=%d，父类别=%s",
                        category.getCategoryName(), category.getCategoryNo(), category.getLevelNo(),
                        req.getParentId() == null ? "无（根类别）" : categoryPath(req.getParentId())));
        return category;
    }

    /** 停用类别：不删除任何行；停用后不可再挂药品或维护模板，历史模板版本仍可被继承/追溯 */
    @Transactional
    public DrugCategory revoke(Long categoryId, String reason) {
        DrugCategory category = requireCategory(categoryId);
        if (CcConstants.REVOKED.equals(category.getStatus())) {
            throw new BusinessException("类别已停用，不能重复停用: " + category.getCategoryNo());
        }
        category.setStatus(CcConstants.REVOKED);
        categoryMapper.updateById(category);
        opLogService.log(CcConstants.ENT_CATEGORY, categoryId, null, CcConstants.ACT_CATEGORY_REVOKE,
                "药品类别停用：" + categoryPath(categoryId) + "（历史模板版本保留可追溯）。原因：" + reason);
        return category;
    }

    public DrugCategory requireCategory(Long categoryId) {
        DrugCategory category = categoryMapper.selectById(categoryId);
        if (category == null) {
            throw new BusinessException("药品类别不存在: " + categoryId);
        }
        return category;
    }

    public DrugCategory requireActiveCategory(Long categoryId) {
        DrugCategory category = requireCategory(categoryId);
        if (CcConstants.REVOKED.equals(category.getStatus())) {
            throw new BusinessException("药品类别已停用，不能操作: " + category.getCategoryNo());
        }
        return category;
    }

    public List<DrugCategory> list() {
        return categoryMapper.selectList(new QueryWrapper<DrugCategory>()
                .orderByAsc("level_no").orderByAsc("id"));
    }

    /**
     * 从叶子类别到根类别的继承链（含叶子自身，根在末尾）。
     * 以 visited 集合兜底任何意外的父链环，避免继承解析死循环。
     */
    public List<DrugCategory> inheritanceChain(Long leafCategoryId) {
        List<DrugCategory> chain = new ArrayList<>();
        Set<Long> visited = new LinkedHashSet<>();
        Long currentId = leafCategoryId;
        while (currentId != null) {
            if (!visited.add(currentId)) {
                throw new BusinessException("药品类别父链存在环，类别ID=" + currentId + "，请联系管理员修正");
            }
            DrugCategory category = requireCategory(currentId);
            chain.add(category);
            currentId = category.getParentId();
        }
        return chain;
    }

    /** 类别全路径名，如 生物制品/疫苗/灭活疫苗 */
    public String categoryPath(Long categoryId) {
        List<DrugCategory> chain = inheritanceChain(categoryId);
        StringBuilder sb = new StringBuilder();
        for (int i = chain.size() - 1; i >= 0; i--) {
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(chain.get(i).getCategoryName());
        }
        return sb.toString();
    }
}
