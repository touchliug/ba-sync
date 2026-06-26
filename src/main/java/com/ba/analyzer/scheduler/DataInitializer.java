package com.ba.analyzer.scheduler;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.SymbolInfo;
import com.ba.analyzer.service.DataSyncService;
import com.ba.analyzer.service.JdbcDataStore;
import com.ba.analyzer.service.SymbolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 启动数据完整性校验器 (ba-sync 专用)。
 *
 * 系统就绪后(ApplicationReadyEvent, 即 SyncScheduler 的 @PostConstruct 预加载之后)运行一次,
 * 逐个 symbol 比对记录数, 只对真正有缺口的 symbol 强制补拉。覆盖两组序列:
 *
 * 日线级 (binance.init.daily, 默认30天, 按天数容差):
 * - 日 K 线 (1d)       : 期望约 N 根
 * - 日线 OI (1d)       : 期望约 N 个点
 * - 资金费率 (8h结算)  : 期望约 N*3 条
 *
 * 中周期级 (同 daily 窗口天数, 按 90% 容差):
 * - 1h / 4h K 线       : 期望约 days*24 / days*6 根
 * - 1h OI              : 期望约 days*24 个点 (分页拉取, 币安单次上限500)
 *
 * 与 SyncScheduler 预加载的区别: 预加载用"90% symbol 命中即整体跳过"的粗启发式, 会漏掉少数缺数据
 * 的 symbol; 本校验器按 symbol 逐个比对记录数。日 K 用币安原生 UTC 日界(北京08:00分界), 均无时区误差。
 * 上市不足窗口的新币天然填不满, 每次启动会被判缺重拉 —— 启动级任务, 可接受。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer {

    /** 资金费率结算频率: 每 8 小时一次 → 每天 3 条。 */
    private static final int FUNDING_RATES_PER_DAY = 3;
    /** 5m K 线 / OI: 每小时 12 根。 */
    private static final int BARS_PER_HOUR_5M = 12;
    private static final long DAY_MS = 86_400_000L;
    private static final long HOUR_MS = 3_600_000L;

    private final SymbolService symbolService;
    private final AppProperties appProperties;
    private final JdbcDataStore dataStore;
    private final DataSyncService dataSyncService;
    private final SyncScheduler syncScheduler;

    @EventListener(ApplicationReadyEvent.class)
    public void verifyAndBackfill() {
        AppProperties.InitConfig cfg = appProperties.getInit();
        if (!cfg.isEnabled()) {
            log.info("Startup data integrity check disabled, skipping");
            return;
        }
        List<String> symbols = syncSymbols();
        if (symbols.isEmpty()) {
            log.warn("No symbols available, skipping startup data integrity check");
            return;
        }
        long now = System.currentTimeMillis();
        log.info("=== Startup data integrity check: {} symbols ===", symbols.size());
        backfillDaily(symbols, cfg.getDaily(), now);
        backfillMidIntervals(symbols, cfg.getDaily().getDays(), now);
        backfillHourlyOi(symbols, cfg.getDaily().getDays(), now);
        log.info("=== Startup data integrity check done ===");
    }

    /** 先从币安同步最新合约列表(写回 symbols.json + 刷新 scheduler 缓存), 失败则回退到本地文件。 */
    private List<String> syncSymbols() {
        try {
            log.info("Refreshing USDT futures symbols from Binance before integrity check");
            List<String> symbols = symbolService.fetchAndSaveSymbols().stream()
                    .map(SymbolInfo::getSymbol)
                    .toList();
            if (!symbols.isEmpty()) {
                // 把最新列表推给 scheduler, 否则运行期任务会沿用启动时(@PostConstruct)读入的旧列表。
                syncScheduler.updateCachedSymbols(symbols);
                return symbols;
            }
            log.warn("Symbol fetch returned empty, falling back to local symbol file");
        } catch (Exception e) {
            log.error("Symbol fetch failed, falling back to local symbol file", e);
        }
        return symbolService.getSymbolNames();
    }

    /** 日线级: 日K / 日线OI / 资金费率, 按天数容差判缺。 */
    private void backfillDaily(List<String> symbols, AppProperties.DailyInit cfg, long now) {
        int days = cfg.getDays();
        long sinceMs = now - (long) days * DAY_MS;
        int klineMin = days - cfg.getToleranceDays();
        int oiMin = days - cfg.getToleranceDays();
        int frMin = (days - cfg.getToleranceDays()) * FUNDING_RATES_PER_DAY;

        List<String> missKline = findMissing(symbols, dataStore.countKlinesSince("1d", sinceMs), klineMin);
        List<String> missOi = findMissing(symbols, dataStore.countOpenInterestSince("1d", sinceMs), oiMin);
        List<String> missFr = findMissing(symbols, dataStore.countFundingRatesSince(sinceMs), frMin);
        log.info("Daily gaps — klines:{} OI:{} funding:{} (window {}d)",
                missKline.size(), missOi.size(), missFr.size(), days);

        dataSyncService.backfillKlines(missKline, "1d", days + 1);
        // 币安 openInterestHist?period=1d 单次上限30天。
        dataSyncService.backfillOi(missOi, "1d", Math.min(days, 30));
        dataSyncService.backfillFundingRates(missFr, days * FUNDING_RATES_PER_DAY + 10);
    }

    /** 1h/4h K线: 按"窗口内根数 < 期望*容差"判缺并补。 */
    private void backfillMidIntervals(List<String> symbols, int days, long now) {
        record Plan(String interval, int barsPerDay) {}
        List<Plan> plans = List.of(new Plan("1h", 24), new Plan("4h", 6));
        long sinceMs = now - (long) days * DAY_MS;
        for (Plan p : plans) {
            int expected = days * p.barsPerDay();
            int minBars = (int) (expected * 0.9);
            List<String> miss = findMissing(symbols, dataStore.countKlinesSince(p.interval(), sinceMs), minBars);
            log.info("Mid-interval gap — {} klines:{} (window {}d, need {}/{})",
                    p.interval(), miss.size(), days, minBars, expected);
            dataSyncService.backfillKlines(miss, p.interval(), expected + p.barsPerDay());
        }
    }

    /** 1h OI: 按窗口内点数判缺, 分页补 (单次上限500)。 */
    private void backfillHourlyOi(List<String> symbols, int days, long now) {
        long sinceMs = now - (long) days * DAY_MS;
        int expected = days * 24;
        int minPts = (int) (expected * 0.9);
        List<String> miss = findMissing(symbols, dataStore.countOpenInterestSince("1h", sinceMs), minPts);
        log.info("Hourly OI gap — {} symbols (window {}d, need {}/{})", miss.size(), days, minPts, expected);
        dataSyncService.backfillOiRange(miss, "1h", sinceMs, now);
    }

    /** 返回记录数不足 minCount(或完全缺失)的 symbol 列表。 */
    private List<String> findMissing(List<String> symbols, Map<String, Integer> counts, int minCount) {
        return symbols.stream()
                .filter(s -> counts.getOrDefault(s, 0) < minCount)
                .toList();
    }
}
