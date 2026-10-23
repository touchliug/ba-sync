package com.ba.analyzer.service;

import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.support.RepoTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class JdbcDataStoreTest {

    private JdbcTemplate jdbc;
    private JdbcDataStore store;

    @BeforeEach
    void setUp() {
        jdbc = RepoTestBase.testJdbc();
        store = new JdbcDataStore(jdbc);
        store.init();
        jdbc.execute("DELETE FROM klines WHERE symbol='TESTUSDT'");
    }

    @Test
    void saveAndGetSymbols_roundTrip() {
        jdbc.execute("DELETE FROM symbols WHERE symbol='TESTUSDT'");
        com.ba.analyzer.model.SymbolInfo s = new com.ba.analyzer.model.SymbolInfo();
        s.setSymbol("TESTUSDT");
        s.setContractType("PERPETUAL");
        s.setStatus("TRADING");
        s.setQuoteAsset("USDT");
        s.setOnboardDate(1600000000000L);

        store.saveSymbols(java.util.List.of(s));
        java.util.List<com.ba.analyzer.model.SymbolInfo> got = store.getSymbols();

        assertThat(got).anyMatch(x -> "TESTUSDT".equals(x.getSymbol())
                && "PERPETUAL".equals(x.getContractType())
                && x.getOnboardDate() == 1600000000000L);
    }

    private static com.ba.analyzer.model.SymbolInfo trading(String symbol) {
        com.ba.analyzer.model.SymbolInfo s = new com.ba.analyzer.model.SymbolInfo();
        s.setSymbol(symbol);
        s.setContractType("PERPETUAL");
        s.setStatus("TRADING");
        s.setQuoteAsset("USDT");
        return s;
    }

    private String statusOf(String symbol) {
        return jdbc.queryForObject("SELECT status FROM symbols WHERE symbol=?", String.class, symbol);
    }

    /** 下架标记会作用于整张表: 测试库里若有非测试的 TRADING 合约则跳过, 避免误改。 */
    private void onlyTestSymbolsTrading() {
        jdbc.update("DELETE FROM symbols WHERE symbol LIKE 'TEST%'");
        Integer others = jdbc.queryForObject("SELECT COUNT(*) FROM symbols WHERE status='TRADING'", Integer.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(others != null && others == 0,
                "test DB holds non-test TRADING symbols, skipping");
    }

    @Test
    void saveSymbols_marksMissingAsDelistedAndRelists() throws InterruptedException {
        onlyTestSymbolsTrading();
        store.saveSymbols(List.of(trading("TESTA"), trading("TESTB"), trading("TESTC")));
        Thread.sleep(5);
        store.saveSymbols(List.of(trading("TESTA"), trading("TESTB")));

        assertThat(statusOf("TESTA")).isEqualTo("TRADING");
        assertThat(statusOf("TESTC")).isEqualTo(JdbcDataStore.STATUS_DELISTED);

        Thread.sleep(5);
        store.saveSymbols(List.of(trading("TESTA"), trading("TESTB"), trading("TESTC")));
        assertThat(statusOf("TESTC")).isEqualTo("TRADING");
    }

    @Test
    void saveSymbols_skipsDelistingWhenListShrinksSharply() throws InterruptedException {
        onlyTestSymbolsTrading();
        store.saveSymbols(List.of(trading("TESTA"), trading("TESTB"), trading("TESTC"),
                trading("TESTD"), trading("TESTE")));
        Thread.sleep(5);
        store.saveSymbols(List.of(trading("TESTA")));

        assertThat(statusOf("TESTE")).isEqualTo("TRADING");
    }

    @Test
    void saveAndGetTickers_roundTrip() {
        jdbc.execute("DELETE FROM ticker_24h WHERE symbol='TESTUSDT'");
        com.ba.analyzer.model.Ticker24h t = new com.ba.analyzer.model.Ticker24h();
        t.setSymbol("TESTUSDT");
        t.setPriceChangePercent("3.3");
        t.setLastPrice("12.5");
        t.setQuoteVolume("999");
        store.saveTickers(java.util.List.of(t));

        java.util.List<com.ba.analyzer.model.Ticker24h> got = store.getLatestTickers();
        assertThat(got).anyMatch(x -> "TESTUSDT".equals(x.getSymbol())
                && "3.3".equals(x.getPriceChangePercent()));
    }

    @Test
    void saveAndGetPremium_roundTrip() {
        jdbc.execute("DELETE FROM premium_index WHERE symbol='TESTUSDT'");
        com.ba.analyzer.model.PremiumIndex p = new com.ba.analyzer.model.PremiumIndex();
        p.setSymbol("TESTUSDT");
        p.setMarkPrice("10.0");
        p.setLastFundingRate("0.0002");
        p.setNextFundingTime(555);
        store.savePremiumIndexes(java.util.List.of(p));

        java.util.List<com.ba.analyzer.model.PremiumIndex> got = store.getLatestPremiumIndexes();
        assertThat(got).anyMatch(x -> "TESTUSDT".equals(x.getSymbol()) && "10.0".equals(x.getMarkPrice()));
    }

    @Test
    void saveAndGetLsr_roundTrip() {
        jdbc.execute("DELETE FROM long_short_ratio WHERE symbol='TESTUSDT'");
        var rows = java.util.List.of(
            new com.ba.analyzer.model.LongShortRatioRow("TESTUSDT", "GLOBAL_ACCOUNT", "1h", 111L, "1.8", "0.64", "0.36"));
        store.saveLongShortRatios(rows);

        var got = store.getLongShortRatio("TESTUSDT", "GLOBAL_ACCOUNT", "1h", 10);
        assertThat(got).hasSize(1);
        assertThat(got.get(0).ratio()).isEqualTo("1.8");
        assertThat(got.get(0).longValue()).isEqualTo("0.64");
    }

    @Test
    void latestKlineOpenTimes_returnsMaxPerSymbol() {
        KlineData k1 = new KlineData();
        k1.setOpenTime(1700000000000L);
        KlineData k2 = new KlineData();
        k2.setOpenTime(1700003600000L);
        store.saveKlines("TESTUSDT", "1h", List.of(k1, k2));

        var latest = store.getLatestKlineOpenTimes("1h");

        assertThat(latest).containsEntry("TESTUSDT", 1700003600000L);
    }

    @Test
    void latestOiTimestamps_returnsMaxPerSymbol() {
        jdbc.execute("DELETE FROM open_interest WHERE symbol='TESTUSDT'");
        com.ba.analyzer.model.OpenInterestData o1 = new com.ba.analyzer.model.OpenInterestData();
        o1.setTimestamp(1700000000000L);
        com.ba.analyzer.model.OpenInterestData o2 = new com.ba.analyzer.model.OpenInterestData();
        o2.setTimestamp(1700003600000L);
        store.saveOpenInterest("TESTUSDT", "1h", List.of(o1, o2));

        var latest = store.getLatestOiTimestamps("1h");

        assertThat(latest).containsEntry("TESTUSDT", 1700003600000L);
    }

    @Test
    void saveAndGetKlines_roundTrip() {
        // 用 no-arg + setter: KlineData 的 @AllArgsConstructor 还含 6 个 transient 缓存字段, 不用它。
        KlineData k = new KlineData();
        k.setOpenTime(1700000000000L);
        k.setOpen("1.0"); k.setHigh("2.0"); k.setLow("0.5"); k.setClose("1.5");
        k.setVolume("100"); k.setCloseTime(1700003599999L); k.setQuoteAssetVolume("150");
        k.setNumberOfTrades(3); k.setTakerBuyBaseAssetVolume("60"); k.setTakerBuyQuoteAssetVolume("90");
        store.saveKlines("TESTUSDT", "1h", List.of(k));

        List<KlineData> got = store.getKlines("TESTUSDT", "1h", 10);

        assertThat(got).hasSize(1);
        assertThat(got.get(0).getOpenTime()).isEqualTo(1700000000000L);
        assertThat(got.get(0).getClose()).isEqualTo("1.5");
    }
}
