package com.ba.analyzer.scheduler;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OI 查询窗口的边界测试。
 *
 * 背景: 币安 openInterestHist 只保留最近 30 天, 且按服务器时钟校验 startTime。
 * 窗口取满 30 天时, 请求真正到达时本地 now 已经过去若干秒 → 稳定 400。
 * 这里锁住"窗口 + 余量"的行为, 防止以后改回 30 天整。
 */
class DataInitializerTest {

    private static final long DAY = 86_400_000L;
    private static final long HOUR = 3_600_000L;

    @Test
    void windowNeverExceedsThirtyDays() {
        long now = 1_700_000_000_000L;
        for (int days : new int[]{1, 7, 29, 30, 60, 365}) {
            long since = DataInitializer.oiWindowStart(now, days);
            assertTrue(since < now, "窗口起点必须在 now 之前: days=" + days);
            assertTrue(now - since <= 30 * DAY, "窗口超过币安 30 天上限: days=" + days);
        }
    }

    @Test
    void thirtyDaysKeepsTwoHourMargin() {
        long now = 1_700_000_000_000L;
        long since = DataInitializer.oiWindowStart(now, 30);
        assertEquals(now - 30 * DAY + 2 * HOUR, since);
    }

    @Test
    void smallerDaysNotClamped() {
        long now = 1_700_000_000_000L;
        assertEquals(now - 7 * DAY, DataInitializer.oiWindowStart(now, 7));
    }
}
