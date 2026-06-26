package com.ba.analyzer.service;

import com.ba.analyzer.model.FundingRateData;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

@Slf4j
@Service
public class JdbcDataStore {

    private final JdbcTemplate jdbc;

    public JdbcDataStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @PostConstruct
    public void init() {
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS klines (
                symbol VARCHAR(20) NOT NULL,
                `interval` VARCHAR(10) NOT NULL,
                open_time BIGINT NOT NULL,
                open VARCHAR(50),
                high VARCHAR(50),
                low VARCHAR(50),
                close VARCHAR(50),
                volume VARCHAR(50),
                close_time BIGINT,
                quote_asset_volume VARCHAR(50),
                number_of_trades INT,
                taker_buy_base VARCHAR(50),
                taker_buy_quote VARCHAR(50),
                PRIMARY KEY (symbol, `interval`, open_time),
                INDEX idx_klines_lookup (symbol, `interval`, open_time DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS funding_rate (
                symbol VARCHAR(20) NOT NULL,
                funding_time BIGINT NOT NULL,
                funding_rate VARCHAR(50),
                PRIMARY KEY (symbol, funding_time),
                INDEX idx_fr_lookup (symbol, funding_time DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS open_interest (
                symbol VARCHAR(20) NOT NULL,
                period VARCHAR(10) NOT NULL DEFAULT '1d',
                `timestamp` BIGINT NOT NULL,
                open_interest VARCHAR(50),
                sum_open_interest VARCHAR(50),
                sum_open_interest_value VARCHAR(50),
                PRIMARY KEY (symbol, period, `timestamp`),
                INDEX idx_oi_lookup (symbol, period, `timestamp` DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS symbols (
                symbol VARCHAR(20) NOT NULL,
                pair VARCHAR(20),
                contract_type VARCHAR(20),
                status VARCHAR(20),
                base_asset VARCHAR(20),
                quote_asset VARCHAR(20),
                price_precision INT,
                quantity_precision INT,
                onboard_date BIGINT,
                delivery_date BIGINT,
                updated_at BIGINT,
                PRIMARY KEY (symbol)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS ticker_24h (
                symbol VARCHAR(20) NOT NULL,
                price_change_percent VARCHAR(30),
                last_price VARCHAR(30),
                open_price VARCHAR(30),
                high_price VARCHAR(30),
                low_price VARCHAR(30),
                weighted_avg_price VARCHAR(30),
                volume VARCHAR(40),
                quote_volume VARCHAR(40),
                trade_count BIGINT,
                open_time BIGINT,
                close_time BIGINT,
                captured_at BIGINT,
                PRIMARY KEY (symbol)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS premium_index (
                symbol VARCHAR(20) NOT NULL,
                mark_price VARCHAR(30),
                index_price VARCHAR(30),
                estimated_settle_price VARCHAR(30),
                last_funding_rate VARCHAR(30),
                next_funding_time BIGINT,
                interest_rate VARCHAR(30),
                `time` BIGINT,
                PRIMARY KEY (symbol)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS long_short_ratio (
                symbol VARCHAR(20) NOT NULL,
                ratio_type VARCHAR(20) NOT NULL,
                period VARCHAR(10) NOT NULL,
                `timestamp` BIGINT NOT NULL,
                long_short_ratio VARCHAR(30),
                long_value VARCHAR(30),
                short_value VARCHAR(30),
                PRIMARY KEY (symbol, ratio_type, period, `timestamp`),
                INDEX idx_lsr_lookup (symbol, ratio_type, period, `timestamp` DESC)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
        """);
        migrateOpenInterestPeriod();
        log.info("MySQL tables ready");
    }

    /**
     * 幂等迁移: 旧 open_interest 表主键为 (symbol, timestamp) 且无 period 列。
     * 检测到无 period 列时, 添加列(默认'1d'回填历史数据)并将主键重建为 (symbol, period, timestamp)。
     * 已是新结构则跳过。
     */
    private void migrateOpenInterestPeriod() {
        Integer hasPeriod = jdbc.queryForObject("""
            SELECT COUNT(*) FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'open_interest' AND COLUMN_NAME = 'period'
        """, Integer.class);
        if (hasPeriod != null && hasPeriod > 0) {
            return;
        }
        log.info("Migrating open_interest: adding period column and rebuilding primary key");
        jdbc.execute("ALTER TABLE open_interest ADD COLUMN period VARCHAR(10) NOT NULL DEFAULT '1d'");
        jdbc.execute("ALTER TABLE open_interest DROP PRIMARY KEY, ADD PRIMARY KEY (symbol, period, `timestamp`)");
        try {
            jdbc.execute("ALTER TABLE open_interest ADD INDEX idx_oi_lookup (symbol, period, `timestamp` DESC)");
        } catch (Exception e) {
            log.warn("idx_oi_lookup may already exist, skipping: {}", e.getMessage());
        }
        log.info("open_interest migration done (existing rows defaulted to period='1d')");
    }

    // ======================== Kline CRUD ========================

    private static final String UPSERT_KLINE = """
        INSERT INTO klines (symbol, `interval`, open_time, open, high, low, close, volume,
            close_time, quote_asset_volume, number_of_trades, taker_buy_base, taker_buy_quote)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE
            open=VALUES(open), high=VALUES(high), low=VALUES(low), close=VALUES(close),
            volume=VALUES(volume), close_time=VALUES(close_time),
            quote_asset_volume=VALUES(quote_asset_volume),
            number_of_trades=VALUES(number_of_trades),
            taker_buy_base=VALUES(taker_buy_base), taker_buy_quote=VALUES(taker_buy_quote)
    """;

    public void saveKlines(String symbol, String interval, List<KlineData> klines) {
        if (klines == null || klines.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>();
        for (KlineData k : klines) {
            batch.add(new Object[]{
                    symbol, interval, k.getOpenTime(),
                    k.getOpen(), k.getHigh(), k.getLow(), k.getClose(),
                    k.getVolume(), k.getCloseTime(), k.getQuoteAssetVolume(),
                    k.getNumberOfTrades(), k.getTakerBuyBaseAssetVolume(), k.getTakerBuyQuoteAssetVolume()
            });
        }
        jdbc.batchUpdate(UPSERT_KLINE, batch);
    }

    public List<KlineData> getKlines(String symbol, String interval, int limit) {
        String sql = "SELECT * FROM klines WHERE symbol=? AND `interval`=? ORDER BY open_time DESC LIMIT ?";
        List<KlineData> result = jdbc.query(sql, this::mapKline, symbol, interval, limit);
        Collections.reverse(result);
        return result;
    }

    public Map<String, List<KlineData>> getKlinesBatch(List<String> symbols, String interval, int limit) {
        Map<String, List<KlineData>> result = new HashMap<>();
        for (String symbol : symbols) {
            List<KlineData> klines = getKlines(symbol, interval, limit);
            if (!klines.isEmpty()) result.put(symbol, klines);
        }
        return result;
    }

    // ======================== Open Interest CRUD ========================

    private static final String UPSERT_OI = """
        INSERT INTO open_interest (symbol, period, `timestamp`, open_interest, sum_open_interest, sum_open_interest_value)
        VALUES (?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE
            open_interest=VALUES(open_interest),
            sum_open_interest=VALUES(sum_open_interest),
            sum_open_interest_value=VALUES(sum_open_interest_value)
    """;

    /** 保存日线OI (向后兼容, period='1d')。 */
    public void saveOpenInterest(String symbol, List<OpenInterestData> dataList) {
        saveOpenInterest(symbol, "1d", dataList);
    }

    public void saveOpenInterest(String symbol, String period, List<OpenInterestData> dataList) {
        if (dataList == null || dataList.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>();
        for (OpenInterestData oi : dataList) {
            long ts = oi.getTimestamp() > 0 ? oi.getTimestamp() : oi.getTime();
            batch.add(new Object[]{symbol, period, ts, oi.getOpenInterest(),
                    oi.getSumOpenInterest(), oi.getSumOpenInterestValue()});
        }
        jdbc.batchUpdate(UPSERT_OI, batch);
    }

    /** 读取日线OI历史 (向后兼容, period='1d')。 */
    public List<OpenInterestData> getOpenInterestHistory(String symbol, int limit) {
        return getOpenInterestHistory(symbol, "1d", limit);
    }

    public List<OpenInterestData> getOpenInterestHistory(String symbol, String period, int limit) {
        String sql = "SELECT * FROM open_interest WHERE symbol=? AND period=? ORDER BY `timestamp` DESC LIMIT ?";
        List<OpenInterestData> result = jdbc.query(sql, this::mapOi, symbol, period, limit);
        Collections.reverse(result);
        return result;
    }

    /** 批量读取日线OI (向后兼容, period='1d')。 */
    public Map<String, List<OpenInterestData>> getOpenInterestBatch(List<String> symbols, int days) {
        return getOpenInterestBatch(symbols, "1d", days);
    }

    public Map<String, List<OpenInterestData>> getOpenInterestBatch(List<String> symbols, String period, int limit) {
        Map<String, List<OpenInterestData>> result = new HashMap<>();
        for (String symbol : symbols) {
            List<OpenInterestData> oi = getOpenInterestHistory(symbol, period, limit);
            if (!oi.isEmpty()) result.put(symbol, oi);
        }
        return result;
    }

    // ======================== Funding Rate CRUD ========================

    private static final String UPSERT_FR = """
        INSERT INTO funding_rate (symbol, funding_time, funding_rate)
        VALUES (?,?,?)
        ON DUPLICATE KEY UPDATE funding_rate=VALUES(funding_rate)
    """;

    public void saveFundingRates(String symbol, List<FundingRateData> rates) {
        if (rates == null || rates.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>();
        for (FundingRateData fr : rates) {
            batch.add(new Object[]{symbol, fr.getFundingTime(), fr.getFundingRate()});
        }
        jdbc.batchUpdate(UPSERT_FR, batch);
    }

    public List<FundingRateData> getFundingRates(String symbol, int limit) {
        String sql = "SELECT * FROM funding_rate WHERE symbol=? ORDER BY funding_time DESC LIMIT ?";
        return jdbc.query(sql, (rs, n) -> {
            FundingRateData fr = new FundingRateData();
            fr.setSymbol(rs.getString("symbol"));
            fr.setFundingTime(rs.getLong("funding_time"));
            fr.setFundingRate(rs.getString("funding_rate"));
            return fr;
        }, symbol, limit);
    }

    // ======================== Symbols CRUD ========================

    private static final String UPSERT_SYMBOL = """
        INSERT INTO symbols (symbol, pair, contract_type, status, base_asset, quote_asset,
            price_precision, quantity_precision, onboard_date, delivery_date, updated_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE pair=VALUES(pair), contract_type=VALUES(contract_type),
            status=VALUES(status), base_asset=VALUES(base_asset), quote_asset=VALUES(quote_asset),
            price_precision=VALUES(price_precision), quantity_precision=VALUES(quantity_precision),
            onboard_date=VALUES(onboard_date), delivery_date=VALUES(delivery_date), updated_at=VALUES(updated_at)
    """;

    public void saveSymbols(List<com.ba.analyzer.model.SymbolInfo> symbols) {
        if (symbols == null || symbols.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<Object[]> batch = new ArrayList<>();
        for (com.ba.analyzer.model.SymbolInfo s : symbols) {
            batch.add(new Object[]{s.getSymbol(), s.getPair(), s.getContractType(), s.getStatus(),
                    s.getBaseAsset(), s.getQuoteAsset(), s.getPricePrecision(), s.getQuantityPrecision(),
                    s.getOnboardDate(), s.getDeliveryDate(), now});
        }
        jdbc.batchUpdate(UPSERT_SYMBOL, batch);
    }

    public List<com.ba.analyzer.model.SymbolInfo> getSymbols() {
        return jdbc.query("SELECT * FROM symbols", (rs, n) -> {
            com.ba.analyzer.model.SymbolInfo s = new com.ba.analyzer.model.SymbolInfo();
            s.setSymbol(rs.getString("symbol"));
            s.setPair(rs.getString("pair"));
            s.setContractType(rs.getString("contract_type"));
            s.setStatus(rs.getString("status"));
            s.setBaseAsset(rs.getString("base_asset"));
            s.setQuoteAsset(rs.getString("quote_asset"));
            s.setPricePrecision(rs.getInt("price_precision"));
            s.setQuantityPrecision(rs.getInt("quantity_precision"));
            s.setOnboardDate(rs.getLong("onboard_date"));
            s.setDeliveryDate(rs.getLong("delivery_date"));
            return s;
        });
    }

    // ======================== Ticker24h CRUD ========================

    private static final String UPSERT_TICKER = """
        INSERT INTO ticker_24h (symbol, price_change_percent, last_price, open_price, high_price,
            low_price, weighted_avg_price, volume, quote_volume, trade_count, open_time, close_time, captured_at)
        VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE price_change_percent=VALUES(price_change_percent),
            last_price=VALUES(last_price), open_price=VALUES(open_price), high_price=VALUES(high_price),
            low_price=VALUES(low_price), weighted_avg_price=VALUES(weighted_avg_price),
            volume=VALUES(volume), quote_volume=VALUES(quote_volume), trade_count=VALUES(trade_count),
            open_time=VALUES(open_time), close_time=VALUES(close_time), captured_at=VALUES(captured_at)
    """;

    public void saveTickers(List<com.ba.analyzer.model.Ticker24h> tickers) {
        if (tickers == null || tickers.isEmpty()) return;
        long now = System.currentTimeMillis();
        List<Object[]> batch = new ArrayList<>();
        for (com.ba.analyzer.model.Ticker24h t : tickers) {
            batch.add(new Object[]{t.getSymbol(), t.getPriceChangePercent(), t.getLastPrice(),
                    t.getOpenPrice(), t.getHighPrice(), t.getLowPrice(), t.getWeightedAvgPrice(),
                    t.getVolume(), t.getQuoteVolume(), t.getCount(), t.getOpenTime(), t.getCloseTime(), now});
        }
        jdbc.batchUpdate(UPSERT_TICKER, batch);
    }

    public List<com.ba.analyzer.model.Ticker24h> getLatestTickers() {
        return jdbc.query("SELECT * FROM ticker_24h", (rs, n) -> {
            com.ba.analyzer.model.Ticker24h t = new com.ba.analyzer.model.Ticker24h();
            t.setSymbol(rs.getString("symbol"));
            t.setPriceChangePercent(rs.getString("price_change_percent"));
            t.setLastPrice(rs.getString("last_price"));
            t.setOpenPrice(rs.getString("open_price"));
            t.setHighPrice(rs.getString("high_price"));
            t.setLowPrice(rs.getString("low_price"));
            t.setWeightedAvgPrice(rs.getString("weighted_avg_price"));
            t.setVolume(rs.getString("volume"));
            t.setQuoteVolume(rs.getString("quote_volume"));
            t.setCount(rs.getLong("trade_count"));
            t.setOpenTime(rs.getLong("open_time"));
            t.setCloseTime(rs.getLong("close_time"));
            return t;
        });
    }

    // ======================== PremiumIndex CRUD ========================

    private static final String UPSERT_PREMIUM = """
        INSERT INTO premium_index (symbol, mark_price, index_price, estimated_settle_price,
            last_funding_rate, next_funding_time, interest_rate, `time`)
        VALUES (?,?,?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE mark_price=VALUES(mark_price), index_price=VALUES(index_price),
            estimated_settle_price=VALUES(estimated_settle_price), last_funding_rate=VALUES(last_funding_rate),
            next_funding_time=VALUES(next_funding_time), interest_rate=VALUES(interest_rate), `time`=VALUES(`time`)
    """;

    public void savePremiumIndexes(List<com.ba.analyzer.model.PremiumIndex> list) {
        if (list == null || list.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>();
        for (com.ba.analyzer.model.PremiumIndex p : list) {
            batch.add(new Object[]{p.getSymbol(), p.getMarkPrice(), p.getIndexPrice(),
                    p.getEstimatedSettlePrice(), p.getLastFundingRate(), p.getNextFundingTime(),
                    p.getInterestRate(), p.getTime()});
        }
        jdbc.batchUpdate(UPSERT_PREMIUM, batch);
    }

    public List<com.ba.analyzer.model.PremiumIndex> getLatestPremiumIndexes() {
        return jdbc.query("SELECT * FROM premium_index", (rs, n) -> {
            com.ba.analyzer.model.PremiumIndex p = new com.ba.analyzer.model.PremiumIndex();
            p.setSymbol(rs.getString("symbol"));
            p.setMarkPrice(rs.getString("mark_price"));
            p.setIndexPrice(rs.getString("index_price"));
            p.setEstimatedSettlePrice(rs.getString("estimated_settle_price"));
            p.setLastFundingRate(rs.getString("last_funding_rate"));
            p.setNextFundingTime(rs.getLong("next_funding_time"));
            p.setInterestRate(rs.getString("interest_rate"));
            p.setTime(rs.getLong("time"));
            return p;
        });
    }

    // ======================== LongShortRatio CRUD ========================

    private static final String UPSERT_LSR = """
        INSERT INTO long_short_ratio (symbol, ratio_type, period, `timestamp`,
            long_short_ratio, long_value, short_value)
        VALUES (?,?,?,?,?,?,?)
        ON DUPLICATE KEY UPDATE long_short_ratio=VALUES(long_short_ratio),
            long_value=VALUES(long_value), short_value=VALUES(short_value)
    """;

    public void saveLongShortRatios(List<com.ba.analyzer.model.LongShortRatioRow> rows) {
        if (rows == null || rows.isEmpty()) return;
        List<Object[]> batch = new ArrayList<>();
        for (com.ba.analyzer.model.LongShortRatioRow r : rows) {
            batch.add(new Object[]{r.symbol(), r.ratioType(), r.period(), r.timestamp(),
                    r.ratio(), r.longValue(), r.shortValue()});
        }
        jdbc.batchUpdate(UPSERT_LSR, batch);
    }

    public List<com.ba.analyzer.model.LongShortRatioRow> getLongShortRatio(
            String symbol, String ratioType, String period, int limit) {
        String sql = "SELECT * FROM long_short_ratio WHERE symbol=? AND ratio_type=? AND period=? " +
                "ORDER BY `timestamp` DESC LIMIT ?";
        List<com.ba.analyzer.model.LongShortRatioRow> result = jdbc.query(sql, (rs, n) ->
                new com.ba.analyzer.model.LongShortRatioRow(
                        rs.getString("symbol"), rs.getString("ratio_type"), rs.getString("period"),
                        rs.getLong("timestamp"), rs.getString("long_short_ratio"),
                        rs.getString("long_value"), rs.getString("short_value")),
                symbol, ratioType, period, limit);
        java.util.Collections.reverse(result);
        return result;
    }

    // ======================== Coverage counts (供启动补数据校验) ========================

    /** 统计每个 symbol 在 [sinceMs, now] 窗口内的 K 线根数 (interval 粒度)。缺数据的 symbol 不出现在结果中。 */
    public Map<String, Integer> countKlinesSince(String interval, long sinceMs) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("SELECT symbol, COUNT(*) c FROM klines WHERE `interval`=? AND open_time >= ? GROUP BY symbol",
                (RowCallbackHandler) rs -> counts.put(rs.getString("symbol"), rs.getInt("c")), interval, sinceMs);
        return counts;
    }

    /** 统计每个 symbol 在 [sinceMs, now] 窗口内的 OI 数据点数 (period 粒度)。 */
    public Map<String, Integer> countOpenInterestSince(String period, long sinceMs) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("SELECT symbol, COUNT(*) c FROM open_interest WHERE period=? AND `timestamp` >= ? GROUP BY symbol",
                (RowCallbackHandler) rs -> counts.put(rs.getString("symbol"), rs.getInt("c")), period, sinceMs);
        return counts;
    }

    /** 统计每个 symbol 在 [sinceMs, now] 窗口内的资金费率条数。 */
    public Map<String, Integer> countFundingRatesSince(long sinceMs) {
        Map<String, Integer> counts = new HashMap<>();
        jdbc.query("SELECT symbol, COUNT(*) c FROM funding_rate WHERE funding_time >= ? GROUP BY symbol",
                (RowCallbackHandler) rs -> counts.put(rs.getString("symbol"), rs.getInt("c")), sinceMs);
        return counts;
    }

    // ======================== Row Mappers ========================

    private KlineData mapKline(ResultSet rs, int rowNum) throws SQLException {
        KlineData k = new KlineData();
        k.setOpenTime(rs.getLong("open_time"));
        k.setOpen(rs.getString("open"));
        k.setHigh(rs.getString("high"));
        k.setLow(rs.getString("low"));
        k.setClose(rs.getString("close"));
        k.setVolume(rs.getString("volume"));
        k.setCloseTime(rs.getLong("close_time"));
        k.setQuoteAssetVolume(rs.getString("quote_asset_volume"));
        k.setNumberOfTrades(rs.getInt("number_of_trades"));
        k.setTakerBuyBaseAssetVolume(rs.getString("taker_buy_base"));
        k.setTakerBuyQuoteAssetVolume(rs.getString("taker_buy_quote"));
        return k;
    }

    private OpenInterestData mapOi(ResultSet rs, int rowNum) throws SQLException {
        OpenInterestData oi = new OpenInterestData();
        oi.setSymbol(rs.getString("symbol"));
        oi.setTimestamp(rs.getLong("timestamp"));
        oi.setOpenInterest(rs.getString("open_interest"));
        oi.setSumOpenInterest(rs.getString("sum_open_interest"));
        oi.setSumOpenInterestValue(rs.getString("sum_open_interest_value"));
        return oi;
    }
}
