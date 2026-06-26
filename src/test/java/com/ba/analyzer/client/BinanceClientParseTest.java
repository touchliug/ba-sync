package com.ba.analyzer.client;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.KlineData;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

class BinanceClientParseTest {

    private MockWebServer server;
    private BinanceClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new MockWebServer();
        server.start();
        AppProperties props = new AppProperties();
        props.setBaseUrl(server.url("/").toString().replaceAll("/$", ""));
        props.getConcurrency().setMaxRequests(2);
        client = new BinanceClient(new OkHttpClient(), props, new ObjectMapper(),
                Executors.newFixedThreadPool(2));
    }

    @AfterEach
    void tearDown() throws Exception {
        server.shutdown();
    }

    @Test
    void getKlines_parsesRawArray() {
        server.enqueue(new MockResponse().setBody(
                "[[1700000000000,\"1.5\",\"2.0\",\"1.0\",\"1.8\",\"100\",1700000299999,\"180\",5,\"60\",\"108\"]]"));

        List<KlineData> klines = client.getKlines("BTCUSDT", "1h", 1);

        assertThat(klines).hasSize(1);
        assertThat(klines.get(0).getOpenTime()).isEqualTo(1700000000000L);
        assertThat(klines.get(0).getClose()).isEqualTo("1.8");
        assertThat(klines.get(0).getNumberOfTrades()).isEqualTo(5);
    }

    @Test
    void getKlines_errorResponse_returnsEmptyNoThrow() {
        // 400 非 2xx 且不重试 → executeRequest 立即返回 "", getKlines 应判空返回空 (不抛 No content to map)。
        server.enqueue(new MockResponse().setResponseCode(400).setBody(""));

        List<KlineData> klines = client.getKlines("BTCUSDT", "1h", 1);

        assertThat(klines).isEmpty();
    }
}
