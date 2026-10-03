package com.evops.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 启用 Spring 定时任务（冷链温控预警扫描等）。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
