package com.ba.analyzer.service;

import com.ba.analyzer.client.BinanceClient;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 数据同步服务 (ba-sync 专用) —— 从币安拉取并写回MySQL。
 *
 * 由原 DataFetchService 的"拉取+写回"那一半抽出。"缓存即数据库"策略:
 * 先读MySQL判断是否足量且新鲜, 命中则跳过API; 否则拉币安并 upsert 写回。
 * 分析服务(ba-analysis)有独立的纯读版同名类, 二者不共享代码。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataSyncService {

    private final BinanceClient binanceClient;
    private final JdbcDataStore dataStore;

    public Map<String, List<KlineData>> fetchDailyKlines(List<String> symbols, int days) {
        return fetchKlinesByInterval(symbols, "1d", days);
    }

    public Map<String, List<KlineData>> fetchKlinesByInterval(List<String> symbols, String interval, int period) {
        Map<String, List<KlineData>> stored = dataStore.getKlinesBatch(symbols, interval, period + 1);
        long intervalMs = intervalToMillis(interval);
        long now = System.currentTimeMillis();
        // 命中条件: 9成symbol已入库, 且每个已入库symbol的最新一根都够新(2个interval内)。
        // 不再要求"每个symbol都满period根": 新上市币天然历史不足period, 强行要求会让单个新币
        // 每轮都触发全量重拉, 旁路掉DB缓存。历史缺口的补齐交由启动期 DataInitializer 按symbol逐个判缺。
        boolean hasEnoughData = stored.size() >= symbols.size() * 0.9
                && stored.values().stream().allMatch(k -> {
                    if (k.isEmpty()) return false;
                    long latestOpenTime = k.get(k.size() - 1).getOpenTime();
                    return now - latestOpenTime < intervalMs * 2;
                });

        if (hasEnoughData) {
            log.info("DB hit for {} klines: {} symbols, {} periods", interval, stored.size(), period);
            return trimKlinesByPeriod(stored, period);
        }

        log.info("Fetching {} klines from API for {} symbols, {} periods (stale or insufficient)", interval, symbols.size(), period);
        ConcurrentMap<String, List<KlineData>> result = new ConcurrentHashMap<>();
        binanceClient.executeConcurrent(symbols, symbol -> {
            List<KlineData> klines = binanceClient.getKlines(symbol, interval, period + 1);
            if (!klines.isEmpty()) {
                result.put(symbol, klines);
                dataStore.saveKlines(symbol, interval, klines);
            }
            return symbol;
        });
        log.info("Fetched and stored {} klines for {} symbols", interval, result.size());
        // 与命中分支一致裁剪到 period (API拉的是 period+1)。
        return trimKlinesByPeriod(result, period);
    }

    /**
     * 强制刷新"当天/当前未收盘那根"K线, 跳过 fetchKlinesByInterval 的 DB 新鲜度判断。
     *
     * 背景: fetchKlinesByInterval 的命中条件 now - latestOpenTime < intervalMs*2 会把当天动态根
     * 也判成"够新→走缓存", 导致它在收盘前不被API刷新。下游需要当天实时K线, 故对最新2根
     * (上一根已收盘 + 当前动态根)做轻量强制拉取并 upsert 覆盖。每币种仅拉2根, 权重很低。
     */
    public void refreshCurrentKline(List<String> symbols, String interval) {
        if (symbols == null || symbols.isEmpty()) return;
        log.info("Refreshing current {} kline (last 2) for {} symbols", interval, symbols.size());
        binanceClient.executeConcurrent(symbols, symbol -> {
            List<KlineData> klines = binanceClient.getKlines(symbol, interval, 2);
            if (!klines.isEmpty()) dataStore.saveKlines(symbol, interval, klines);
            return symbol;
        });
    }

    /** 拉全市场标记价/溢价(单次批量)并 upsert 最新快照。 */
    public void syncPremiumIndex() {
        List<com.ba.analyzer.model.PremiumIndex> list = binanceClient.getPremiumIndexes();
        if (!list.isEmpty()) {
            dataStore.savePremiumIndexes(list);
            log.info("Synced {} premium index", list.size());
        }
    }

    /** 逐 symbol × 4 端点拉多空比, 归一化为 row 后 upsert。吃 /futures/data 次数桶, 由调用方控频率。 */
    public void syncLongShortRatio(List<String> symbols, String period, int limit) {
        for (com.ba.analyzer.client.LsrType type : com.ba.analyzer.client.LsrType.values()) {
            binanceClient.executeConcurrent(symbols, symbol -> {
                List<com.ba.analyzer.model.LongShortRatio> raw =
                        binanceClient.getLongShortRatio(symbol, type, period, limit);
                if (raw.isEmpty()) return symbol;
                List<com.ba.analyzer.model.LongShortRatioRow> rows = new java.util.ArrayList<>();
                for (com.ba.analyzer.model.LongShortRatio r : raw) {
                    String ratio = type.isTaker() ? r.getBuySellRatio() : r.getLongShortRatio();
                    String lv = type.isTaker() ? r.getBuyVol() : r.getLongAccount();
                    String sv = type.isTaker() ? r.getSellVol() : r.getShortAccount();
                    rows.add(new com.ba.analyzer.model.LongShortRatioRow(
                            symbol, type.dbValue(), period, r.getTimestamp(), ratio, lv, sv));
                }
                dataStore.saveLongShortRatios(rows);
                return symbol;
            });
        }
        log.info("Long/short ratio sync done for {} symbols × 4 types", symbols.size());
    }

    /** 拉全市场24h行情(单次批量)并 upsert 最新快照。 */
    public void syncTickers() {
        List<com.ba.analyzer.model.Ticker24h> tickers = binanceClient.get24hrTickers();
        if (!tickers.isEmpty()) {
            dataStore.saveTickers(tickers);
            log.info("Synced {} 24h tickers", tickers.size());
        }
    }

    public void syncFundingRates(List<String> symbols) {
        log.info("Syncing funding rates for {} symbols", symbols.size());
        binanceClient.executeConcurrent(symbols, symbol -> {
            var rates = binanceClient.getFundingRates(symbol, 10);
            if (!rates.isEmpty()) dataStore.saveFundingRates(symbol, rates);
            return symbol;
        });
        log.info("Funding rate sync done");
    }

    /**
     * 按指定周期(5m/1h/6h/1d等)批量获取OI历史并写回。
     * 先读DB(对应period), 命中率达标且新鲜则直接返回; 否则从币安openInterestHist拉取并写回。
     */
    public Map<String, List<OpenInterestData>> fetchOiHistoryByPeriod(List<String> symbols, String period, int limit) {
        Map<String, List<OpenInterestData>> stored = dataStore.getOpenInterestBatch(symbols, period, limit);
        // 所有周期(含1d)都走新鲜度判断: 币安 openInterestHist 每次返回最近30天且按UTC零点对齐,
        // 配合 upsert + 永不删除, 日线序列随时间无限累积(突破币安30天上限)。
        boolean fresh = isOiFresh(stored, period);
        if (stored.size() >= symbols.size() * 0.9 && fresh) {
            log.info("DB hit for {} OI history batch: {} symbols", period, stored.size());
            return stored;
        }
        log.info("Fetching {} OI history from API for {} symbols, {} periods", period, symbols.size(), limit);
        ConcurrentMap<String, List<OpenInterestData>> result = new ConcurrentHashMap<>();
        binanceClient.executeConcurrent(symbols, symbol -> {
            List<OpenInterestData> oiHistory = binanceClient.getOpenInterestHistory(symbol, period, limit);
            if (!oiHistory.isEmpty()) {
                result.put(symbol, oiHistory);
                dataStore.saveOpenInterest(symbol, period, oiHistory);
            }
            return symbol;
        });
        log.info("Fetched and stored {} OI history for {} symbols", period, result.size());
        return result;
    }

    // ======================== 强制补数据 (供启动校验, 跳过命中率/新鲜度启发式) ========================

    /** 对指定的(缺数据)symbol 强制从币安拉取 K 线并写回, 不做任何 DB 命中判断。 */
    public void backfillKlines(List<String> symbols, String interval, int limit) {
        if (symbols.isEmpty()) return;
        log.info("Backfilling {} klines for {} symbols, {} periods", interval, symbols.size(), limit);
        binanceClient.executeConcurrent(symbols, symbol -> {
            List<KlineData> klines = binanceClient.getKlines(symbol, interval, limit);
            if (!klines.isEmpty()) dataStore.saveKlines(symbol, interval, klines);
            return symbol;
        });
    }

    /** 对指定的(缺数据)symbol 强制拉取 OI 历史并写回。 */
    public void backfillOi(List<String> symbols, String period, int limit) {
        if (symbols.isEmpty()) return;
        log.info("Backfilling {} OI for {} symbols, {} periods", period, symbols.size(), limit);
        binanceClient.executeConcurrent(symbols, symbol -> {
            List<OpenInterestData> oi = binanceClient.getOpenInterestHistory(symbol, period, limit);
            if (!oi.isEmpty()) dataStore.saveOpenInterest(symbol, period, oi);
            return symbol;
        });
    }

    /** 对指定的(缺数据)symbol 按时间区间分页拉取 OI 并写回 (用于 5m 等单次拉不满的短周期)。 */
    public void backfillOiRange(List<String> symbols, String period, long startMs, long endMs) {
        if (symbols.isEmpty()) return;
        log.info("Backfilling {} OI (paged) for {} symbols, window [{}, {}]", period, symbols.size(), startMs, endMs);
        binanceClient.executeConcurrent(symbols, symbol -> {
            List<OpenInterestData> oi = binanceClient.getOpenInterestHistoryRange(symbol, period, startMs, endMs);
            if (!oi.isEmpty()) dataStore.saveOpenInterest(symbol, period, oi);
            return symbol;
        });
    }

    /** 对指定的(缺数据)symbol 强制拉取资金费率并写回。 */
    public void backfillFundingRates(List<String> symbols, int limit) {
        if (symbols.isEmpty()) return;
        log.info("Backfilling funding rates for {} symbols, {} periods", symbols.size(), limit);
        binanceClient.executeConcurrent(symbols, symbol -> {
            var rates = binanceClient.getFundingRates(symbol, limit);
            if (!rates.isEmpty()) dataStore.saveFundingRates(symbol, rates);
            return symbol;
        });
    }

    private boolean isOiFresh(Map<String, List<OpenInterestData>> stored, String period) {
        if (stored.isEmpty()) return false;
        long periodMs = intervalToMillis(period);
        long now = System.currentTimeMillis();
        return stored.values().stream().allMatch(list -> {
            if (list.isEmpty()) return false;
            OpenInterestData latest = list.get(list.size() - 1);
            long ts = latest.getTimestamp() > 0 ? latest.getTimestamp() : latest.getTime();
            return now - ts < periodMs * 3;
        });
    }

    private long intervalToMillis(String interval) {
        return switch (interval) {
            case "1m" -> 60_000L;
            case "3m" -> 180_000L;
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "30m" -> 1_800_000L;
            case "1h" -> 3_600_000L;
            case "2h" -> 7_200_000L;
            case "4h" -> 14_400_000L;
            case "6h" -> 21_600_000L;
            case "8h" -> 28_800_000L;
            case "12h" -> 43_200_000L;
            case "1d" -> 86_400_000L;
            default -> 86_400_000L;
        };
    }

    private Map<String, List<KlineData>> trimKlinesByPeriod(Map<String, List<KlineData>> klineMap, int period) {
        Map<String, List<KlineData>> result = new ConcurrentHashMap<>();
        for (Map.Entry<String, List<KlineData>> entry : klineMap.entrySet()) {
            List<KlineData> klines = entry.getValue();
            if (klines.size() <= period) {
                result.put(entry.getKey(), klines);
            } else {
                result.put(entry.getKey(), klines.subList(klines.size() - period, klines.size()));
            }
        }
        return result;
    }
}
