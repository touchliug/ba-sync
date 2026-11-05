package com.ba.analyzer.scheduler;

import com.ba.analyzer.service.RetentionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 下架币清理调度器。
 *
 * <p>默认每天凌晨 4:30（北京时间）执行，避开：
 * <ul>
 *   <li>06:00 的合约列表更新（此时状态还在变，清理容易误伤）</li>
 *   <li>08:10 的日线 OI 同步</li>
 *   <li>每 3 分钟一轮的高频同步任务（大表 DELETE 会抢占 IO）</li>
 * </ul>
 *
 * <p>任务本身有宽限期与单次上限保护，这里只负责触发与兜底异常。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetentionScheduler {

    private final RetentionService retentionService;

    @Scheduled(cron = "${binance.retention.cron}")
    public void purgeDelistedSymbols() {
        log.info("=== Scheduled: purge delisted symbols ===");
        try {
            retentionService.purgeDelisted();
        } catch (Exception e) {
            log.error("Delisted symbol purge failed", e);
        }
    }
}
