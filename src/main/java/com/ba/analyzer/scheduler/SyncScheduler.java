package com.ba.analyzer.scheduler;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.service.DataSyncService;
import com.ba.analyzer.service.SymbolService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 数据同步调度器 (ba-sync 专用)。
 *
 * 只负责把币安数据拉进MySQL, 不做任何分析。各任务cron集中在 application.yml 的 binance.schedule.*:
 * - symbol-update    : 更新USDT永续合约列表
 * - short-term-sync  : 每5分钟刷新日K/5m K/5m OI/资金费率 (供分析服务读取; 日K含当天动态那根)
 * - daily-oi-sync    : 每日同步日线OI (upsert永不删除 → 长期累积, 突破币安30天上限)
 *
 * 启动时的数据预加载/补齐已统一由 DataInitializer(ApplicationReadyEvent)负责, 本类不再做 @PostConstruct 预加载。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SyncScheduler {

    private final SymbolService symbolService;
    private final AppProperties appProperties;
    private final DataSyncService dataSyncService;

    /** 5m OI拉取周期数: 288根=24h, 取300留余量以覆盖24h窗口。 */
    private static final int INTRADAY_OI_PERIODS = 300;

    private volatile List<String> cachedSymbols = List.of();
    private final Object symbolsLock = new Object();

    @Scheduled(cron = "${binance.schedule.daily-oi-sync}")
    public void syncDailyOi() {
        try {
            List<String> symbols = getSymbols();
            // 30 = 币安 openInterestHist?period=1d 单次最大返回天数; upsert 永不删除 → 序列累积。
            // 用 backfillOi(无条件拉) 而非 fetchOiHistoryByPeriod: 后者带3天新鲜度门槛, 稳态下会
            // 把"今天的00:00快照"判成够新而跳过, 导致最新日OI点滞后1~3天。本任务一天仅一次、幂等,
            // 无需省配额, 每天直接拉确保当天00:00 UTC快照入库 (实测1d点定格日界、值盘中不滚动)。
            log.info("Daily OI sync: {} symbols", symbols.size());
            dataSyncService.backfillOi(symbols, "1d", 30);
        } catch (Exception e) {
            log.error("Daily OI sync failed", e);
        }
    }

    @Scheduled(cron = "${binance.schedule.symbol-update}")
    public void updateSymbols() {
        log.info("=== Scheduled: Updating USDT futures symbols ===");
        try {
            List<String> symbols = symbolService.fetchAndSaveSymbols().stream()
                    .map(s -> s.getSymbol())
                    .toList();
            synchronized (symbolsLock) {
                cachedSymbols = symbols;
            }
            log.info("Updated {} symbols", symbols.size());
        } catch (Exception e) {
            log.error("Failed to update symbols", e);
        }
    }

    @Scheduled(cron = "${binance.schedule.short-term-sync}")
    public void syncShortTerm() {
        log.info("=== Scheduled: Short-term data sync ===");
        try {
            List<String> symbols = getSymbols();
            int days = dailyDays();
            dataSyncService.fetchDailyKlines(symbols, days);
            // 历史序列走缓存省配额, 但当天日线那根是动态的: 强制刷新最新2根, 保证下游拿到实时值。
            dataSyncService.refreshCurrentKline(symbols, "1d");
            var stCfg = appProperties.getAnalysis().getShortTermRise();
            if (stCfg.isEnabled()) {
                int shortPeriod = stCfg.getPeriod() + appProperties.getConcurrency().getHistoryBufferDays();
                dataSyncService.fetchKlinesByInterval(symbols, stCfg.getInterval(), shortPeriod);
                // 短期K线同理: 强制刷新当前未收盘那根。
                dataSyncService.refreshCurrentKline(symbols, stCfg.getInterval());
            }
            dataSyncService.syncIntradayOi(symbols, INTRADAY_OI_PERIODS);
            dataSyncService.syncFundingRates(symbols);
            log.info("Daily/5m klines, 5m OI, and funding rates refreshed");
        } catch (Exception e) {
            log.error("Short-term data sync failed", e);
        }
    }

    private int dailyDays() {
        return appProperties.getInit().getDaily().getDays();
    }

    /** 用最新合约列表覆盖内存缓存。供 DataInitializer 启动同步合约后调用, 避免运行期任务沿用启动时的旧列表。 */
    public void updateCachedSymbols(List<String> symbols) {
        if (symbols == null || symbols.isEmpty()) return;
        synchronized (symbolsLock) {
            cachedSymbols = symbols;
        }
        log.info("SyncScheduler symbol cache refreshed: {} symbols", symbols.size());
    }

    private List<String> getSymbols() {
        if (cachedSymbols.isEmpty()) {
            synchronized (symbolsLock) {
                if (cachedSymbols.isEmpty()) {
                    cachedSymbols = symbolService.getSymbolNames();
                }
            }
        }
        return cachedSymbols;
    }
}
