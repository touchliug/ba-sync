package com.ba.analyzer.config;

import jakarta.annotation.PreDestroy;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 定时任务调度配置类
 * 配置3个线程的调度线程池，支持多个定时任务并行执行
 */
@Configuration
public class ScheduleConfig implements SchedulingConfigurer {

    private ScheduledExecutorService scheduler;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        scheduler = Executors.newScheduledThreadPool(3);
        taskRegistrar.setScheduler(scheduler);
    }

    @PreDestroy
    public void shutdown() {
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
}
