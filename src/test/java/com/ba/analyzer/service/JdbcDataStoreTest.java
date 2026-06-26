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
