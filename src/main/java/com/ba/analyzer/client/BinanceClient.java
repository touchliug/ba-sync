package com.ba.analyzer.client;

import com.ba.analyzer.config.AppProperties;
import com.ba.analyzer.model.FundingRateData;
import com.ba.analyzer.model.KlineData;
import com.ba.analyzer.model.OpenInterestData;
import com.ba.analyzer.model.SymbolInfo;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 币安API客户端
 * 封装币安USDT永续合约的REST API调用，包括：
 * - 获取交易所合约信息(exchangeInfo)
 * - 获取K线数据(klines)
 * - 获取持仓量数据(openInterest)
 * - 并发请求控制(Semaphore限流 + 复用线程池)
 */
@Slf4j
@Component
public class BinanceClient {

    private final OkHttpClient httpClient;
    private final AppProperties appProperties;
    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final Semaphore semaphore;

    public BinanceClient(OkHttpClient httpClient, AppProperties appProperties,
                         ObjectMapper objectMapper, @Qualifier("httpExecutor") ExecutorService executorService) {
        this.httpClient = httpClient;
        this.appProperties = appProperties;
        this.objectMapper = objectMapper;
        this.executorService = executorService;
        this.semaphore = new Semaphore(appProperties.getConcurrency().getMaxRequests());
    }

    public List<SymbolInfo> getUsdtFuturesSymbols() {
        String url = appProperties.getBaseUrl() + "/fapi/v1/exchangeInfo";
        String json = executeRequest(url);
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode symbolsNode = root.get("symbols");
            List<SymbolInfo> allSymbols = objectMapper.readValue(
                    symbolsNode.toString(), new TypeReference<List<SymbolInfo>>() {});

            return allSymbols.stream()
                    .filter(s -> "PERPETUAL".equals(s.getContractType()))
                    .filter(s -> "USDT".equals(s.getQuoteAsset()))
                    .filter(s -> "TRADING".equals(s.getStatus()))
                    .toList();
        } catch (JsonProcessingException e) {
            log.error("Failed to parse exchange info", e);
            return Collections.emptyList();
        }
    }

    public List<KlineData> getKlines(String symbol, String interval, int limit) {
        String url = UriComponentsBuilder.fromHttpUrl(appProperties.getBaseUrl())
                .path("/fapi/v1/klines")
                .queryParam("symbol", symbol)
                .queryParam("interval", interval)
                .queryParam("limit", limit)
                .build().toUriString();
        String json = executeRequest(url);
        try {
            List<List<Object>> rawKlines = objectMapper.readValue(
                    json, new TypeReference<List<List<Object>>>() {});
            List<KlineData> klines = new ArrayList<>();
            for (List<Object> raw : rawKlines) {
                KlineData kline = new KlineData();
                kline.setOpenTime(toLong(raw.get(0)));
                kline.setOpen(toString(raw.get(1)));
                kline.setHigh(toString(raw.get(2)));
                kline.setLow(toString(raw.get(3)));
                kline.setClose(toString(raw.get(4)));
                kline.setVolume(toString(raw.get(5)));
                kline.setCloseTime(toLong(raw.get(6)));
                kline.setQuoteAssetVolume(toString(raw.get(7)));
                kline.setNumberOfTrades(toInt(raw.get(8)));
                kline.setTakerBuyBaseAssetVolume(toString(raw.get(9)));
                kline.setTakerBuyQuoteAssetVolume(toString(raw.get(10)));
                klines.add(kline);
            }
            return klines;
        } catch (JsonProcessingException e) {
            log.error("Failed to parse klines for symbol: {}", symbol, e);
            return Collections.emptyList();
        }
    }

    public OpenInterestData getOpenInterest(String symbol) {
        String url = UriComponentsBuilder.fromHttpUrl(appProperties.getBaseUrl())
                .path("/fapi/v1/openInterest")
                .queryParam("symbol", symbol)
                .build().toUriString();
        String json = executeRequest(url);
        if (json.isEmpty()) return null;
        try {
            return objectMapper.readValue(json, OpenInterestData.class);
        } catch (JsonProcessingException e) {
            log.error("Failed to parse open interest for symbol: {}", symbol, e);
            return null;
        }
    }

    public List<OpenInterestData> getOpenInterestHistory(String symbol, String period, int limit) {
        String url = UriComponentsBuilder.fromHttpUrl(appProperties.getBaseUrl())
                .path("/futures/data/openInterestHist")
                .queryParam("symbol", symbol)
                .queryParam("period", period)
                .queryParam("limit", limit)
                .build().toUriString();
        String json = executeRequest(url);
        try {
            if (json.isEmpty()) return Collections.emptyList();
            return objectMapper.readValue(json, new TypeReference<List<OpenInterestData>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse open interest history for symbol: {}", symbol, e);
            return Collections.emptyList();
        }
    }

    public List<FundingRateData> getFundingRates(String symbol, int limit) {
        String url = UriComponentsBuilder.fromHttpUrl(appProperties.getBaseUrl())
                .path("/fapi/v1/fundingRate")
                .queryParam("symbol", symbol)
                .queryParam("limit", limit)
                .build().toUriString();
        String json = executeRequest(url);
        try {
            if (json.isEmpty()) return Collections.emptyList();
            return objectMapper.readValue(json, new TypeReference<List<FundingRateData>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse funding rates for symbol: {}", symbol, e);
            return Collections.emptyList();
        }
    }

    public <T> List<T> executeConcurrent(List<String> symbols, Function<String, T> task) {
        long intervalMs = appProperties.getConcurrency().getRequestIntervalMs();
        int timeoutSeconds = appProperties.getConcurrency().getTaskTimeoutSeconds();
        List<CompletableFuture<T>> futures = new ArrayList<>();

        for (int i = 0; i < symbols.size(); i++) {
            final String symbol = symbols.get(i);
            long delay = i * intervalMs;

            CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
                try {
                    semaphore.acquire();
                    try {
                        return task.apply(symbol);
                    } finally {
                        semaphore.release();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }, delay > 0
                    ? CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS, executorService)
                    : executorService);

            futures.add(future);
        }

        return futures.stream()
                .map(f -> {
                    try {
                        return f.get(timeoutSeconds, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        log.error("Concurrent task failed", e);
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private String executeRequest(String url) {
        int maxRetries = 3;
        int[] backoffMs = {500, 1500, 3000};

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.code() == 429 || response.code() == 418) {
                    if (attempt < maxRetries) {
                        log.warn("Rate limited ({}), retrying in {}ms: {}", response.code(), backoffMs[attempt], url);
                        Thread.sleep(backoffMs[attempt]);
                        continue;
                    }
                }
                if (response.code() >= 500 && attempt < maxRetries) {
                    log.warn("Server error ({}), retrying in {}ms: {}", response.code(), backoffMs[attempt], url);
                    Thread.sleep(backoffMs[attempt]);
                    continue;
                }
                if (!response.isSuccessful() || response.body() == null) {
                    log.error("Request failed: {} - status: {}", url, response.code());
                    return "";
                }
                return response.body().string();
            } catch (IOException e) {
                if (attempt < maxRetries) {
                    log.warn("Request error (attempt {}): {}", attempt + 1, url);
                    try { Thread.sleep(backoffMs[attempt]); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); return ""; }
                } else {
                    log.error("Request failed after {} retries: {}", maxRetries, url, e);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return "";
            }
        }
        return "";
    }

    private long toLong(Object obj) {
        if (obj instanceof Number) return ((Number) obj).longValue();
        return Long.parseLong(obj.toString());
    }

    private String toString(Object obj) {
        return obj.toString();
    }

    private int toInt(Object obj) {
        if (obj instanceof Number) return ((Number) obj).intValue();
        return Integer.parseInt(obj.toString());
    }
}
