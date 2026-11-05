package com.ba.analyzer.service;

import com.ba.analyzer.config.AppProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 下架币清理的安全阀测试。
 *
 * <p>重点锁住「单次上限」——这是防止某次 exchangeInfo 异常导致大批误标后清库的最后一道闸。
 */
class RetentionServiceTest {

    @Test
    void abortsWhenCandidatesExceedCap() {
        assertTrue(RetentionService.shouldAbort(21, 20), "超过上限必须中止");
        assertFalse(RetentionService.shouldAbort(20, 20), "恰好等于上限应放行");
        assertFalse(RetentionService.shouldAbort(1, 20));
    }

    @Test
    void defaultsAreSafe() {
        AppProperties.RetentionConfig cfg = new AppProperties.RetentionConfig();
        assertTrue(cfg.isEnabled());
        assertEquals(7, cfg.getDelistedGraceDays(), "默认宽限期 7 天，给误判留出发现窗口");
        assertEquals(20, cfg.getMaxSymbolsPerRun());
        assertEquals(90, cfg.getBackupKeepDays());
        assertTrue(cfg.isDeleteSymbolsRow(), "默认连 symbols 行一起删");
        assertTrue(cfg.getCron().contains("30 4"), "默认凌晨 4:30，避开其他同步任务");
    }

    @Test
    void capBoundaryIsInclusive() {
        // 0 个候选：不触发（早退在调用方，这里只验证边界不发生越界）
        assertFalse(RetentionService.shouldAbort(0, 20));
    }
}
