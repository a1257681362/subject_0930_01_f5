package com.evops.coldchain.job;

import com.evops.coldchain.service.AlertScanService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;

/**
 * 温控预警定时扫描任务（V5）。
 *
 * 重启安全：扫描是对批次温度留痕的确定性重放，预警以
 * “批次|采样点(探头)|方向|连续段起点”为唯一键只追加——
 * 应用重启（或多实例并发）后再次执行不会多写记录（命中去重跳过），
 * 也不会丢操作者（扫描动作与预警行都带操作者审计：定时为 system、手工触发为登录管理员）。
 */
@Component
public class AlertScanJob {

    private static final Logger log = LoggerFactory.getLogger(AlertScanJob.class);

    @Resource
    private AlertScanService alertScanService;

    /** 每 5 分钟扫描一次（fixedDelay：上一轮结束后再计时，避免任务重叠） */
    @Scheduled(fixedDelayString = "${cold-chain.warning-scan.fixed-delay-ms:300000}",
            initialDelayString = "${cold-chain.warning-scan.initial-delay-ms:10000}")
    public void scan() {
        try {
            AlertScanService.ScanResult result = alertScanService.scanAll();
            if (result.inserted > 0 || result.duplicated > 0) {
                log.info("温控预警定时扫描：扫描批次={}，新增预警={}，去重跳过={}",
                        result.scannedBatches, result.inserted, result.duplicated);
            }
        } catch (Exception ex) {
            // 单轮异常不影响下一轮调度；预警只追加，已提交的预警不会因异常回滚消失
            log.error("温控预警定时扫描本轮失败，下一轮继续", ex);
        }
    }
}
