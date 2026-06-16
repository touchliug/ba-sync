package com.ba.analyzer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.TimeZone;

/**
 * 币安数据同步服务启动类 (ba-sync)。
 *
 * 职责: 从币安API拉取合约列表/K线/持仓量/资金费率, 持久化到MySQL。
 * 与分析服务(ba-analysis)完全独立, 仅通过MySQL交换数据 —— 本进程只写库, 不做任何分析。
 */
@SpringBootApplication
@EnableScheduling
public class SyncApplication {

    public static void main(String[] args) {
        // JVM 固定 Asia/Shanghai: 行为与北京开发环境一致, 不随部署服务器时区漂移。
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        SpringApplication.run(SyncApplication.class, args);
    }
}
