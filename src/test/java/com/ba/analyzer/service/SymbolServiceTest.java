package com.ba.analyzer.service;

import com.ba.analyzer.client.BinanceClient;
import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.SymbolInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SymbolServiceTest {

    @TempDir
    Path tmp;

    private BinanceClient binanceClient;
    private JdbcDataStore dataStore;
    private SymbolService service;
    private Path file;

    @BeforeEach
    void setUp() {
        binanceClient = mock(BinanceClient.class);
        dataStore = mock(JdbcDataStore.class);
        file = tmp.resolve("data/symbols.json");
        AppProperties props = new AppProperties();
        props.getSymbol().setFilePath(file.toString());
        service = new SymbolService(binanceClient, props, new ObjectMapper(), dataStore);
    }

    private static SymbolInfo symbol(String name) {
        SymbolInfo s = new SymbolInfo();
        s.setSymbol(name);
        s.setStatus("TRADING");
        return s;
    }

    @Test
    void emptyFetchKeepsFileAndDbUntouched() throws Exception {
        Files.createDirectories(file.getParent());
        String fallback = "[{\"symbol\":\"BTCUSDT\"}]";
        Files.writeString(file, fallback);
        when(binanceClient.getUsdtFuturesSymbols()).thenReturn(List.of());

        assertThat(service.fetchAndSaveSymbols()).isEmpty();

        assertThat(Files.readString(file)).isEqualTo(fallback);
        verify(dataStore, never()).saveSymbols(any());
        assertThat(service.getSymbolNames()).containsExactly("BTCUSDT");
    }

    @Test
    void successfulFetchWritesFileAndDb() throws Exception {
        when(binanceClient.getUsdtFuturesSymbols()).thenReturn(List.of(symbol("BTCUSDT"), symbol("ETHUSDT")));

        service.fetchAndSaveSymbols();

        assertThat(service.getSymbolNames()).containsExactly("BTCUSDT", "ETHUSDT");
        verify(dataStore).saveSymbols(any());
    }
}
