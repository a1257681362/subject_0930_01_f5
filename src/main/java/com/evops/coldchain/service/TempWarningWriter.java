package com.evops.coldchain.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.evops.coldchain.entity.TempWarning;
import com.evops.coldchain.mapper.TempWarningMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;

/**
 * 温度预警写入器：每条预警独立事务（REQUIRES_NEW）提交。
 * 定时任务重启/并发重放同一段时，唯一键冲突只回滚这一条写入，
 * 不影响本批次其他预警，调用方将其计为“去重跳过”。
 */
@Service
public class TempWarningWriter {

    private static final Logger log = LoggerFactory.getLogger(TempWarningWriter.class);

    @Resource
    private TempWarningMapper warningMapper;

    /**
     * 不存在则插入。
     * @return true=新插入；false=同 dedupKey 预警已存在（重启/并发重复扫描），不多写
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean insertIfAbsent(TempWarning warning) {
        Long existing = warningMapper.selectCount(new QueryWrapper<TempWarning>()
                .eq("dedup_key", warning.getDedupKey()));
        if (existing != null && existing > 0) {
            return false;
        }
        try {
            warningMapper.insert(warning);
            return true;
        } catch (DuplicateKeyException dup) {
            // 并发竞态兜底：两个扫描实例同时通过了上面的存在性检查
            log.info("温控预警唯一键并发冲突，去重跳过：dedupKey={}", warning.getDedupKey());
            return false;
        }
    }
}
