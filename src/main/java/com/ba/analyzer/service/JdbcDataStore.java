package com.ba.analyzer.service;

import com.ba.analyzer.model.FundingRateData;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
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
