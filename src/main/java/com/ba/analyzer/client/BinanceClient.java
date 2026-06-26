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
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
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
    /** 全局限流冷却截止时刻(ms): 任一线程收到429/418后置位, 所有线程在该时刻前先等待。 */
    private final AtomicLong rateLimitedUntilMs = new AtomicLong(0);

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
        if (json.isEmpty()) {
            log.error("Exchange info request returned empty body");
            return Collections.emptyList();
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode symbolsNode = root.get("symbols");
            if (symbolsNode == null || !symbolsNode.isArray()) {
                // 错误响应 (如 {"code":..,"msg":..}) 没有 symbols 数组: 不要让 .toString() 抛 NPE,
                // 走兜底返回空 (上层 SymbolService 会回退到本地 symbols.json)。
                log.error("Exchange info missing 'symbols' array, body head: {}",
                        json.substring(0, Math.min(json.length(), 200)));
                return Collections.emptyList();
            }
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
        if (json.isEmpty()) return Collections.emptyList();
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

    /**
     * 分页拉取 OI 历史 [startMs, endMs]。币安 openInterestHist 单次上限 500,
     * 故按时间窗向后翻页直到覆盖整个区间或拿不到更多数据。仅 5m/15m 等短周期补数据用得到。
     */
    public List<OpenInterestData> getOpenInterestHistoryRange(String symbol, String period, long startMs, long endMs) {
        long periodMs = periodToMillis(period);
        List<OpenInterestData> all = new ArrayList<>();
        long cursor = startMs;
        // 最多翻 50 页作为失控保护 (50*500=25000 点, 远超任何合理窗口)。
        for (int page = 0; page < 50 && cursor <= endMs; page++) {
            String url = UriComponentsBuilder.fromHttpUrl(appProperties.getBaseUrl())
                    .path("/futures/data/openInterestHist")
                    .queryParam("symbol", symbol)
                    .queryParam("period", period)
                    .queryParam("limit", 500)
                    .queryParam("startTime", cursor)
                    .queryParam("endTime", endMs)
                    .build().toUriString();
            String json = executeRequest(url);
            if (json.isEmpty()) break;
            List<OpenInterestData> batch;
            try {
                batch = objectMapper.readValue(json, new TypeReference<List<OpenInterestData>>() {});
            } catch (JsonProcessingException e) {
                log.error("Failed to parse OI history range for symbol: {}", symbol, e);
                break;
            }
            if (batch.isEmpty()) break;
            all.addAll(batch);
            long lastTs = batch.get(batch.size() - 1).getTimestamp();
            if (batch.size() < 500) break;          // 不足一页 → 已到区间末尾
            cursor = lastTs + periodMs;              // 下一页从最后一点之后开始
        }
        return all;
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

        List<T> results = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            try {
                T result = futures.get(i).get(timeoutSeconds, TimeUnit.SECONDS);
                if (result != null) results.add(result);
            } catch (TimeoutException e) {
                // 打印具体 symbol: 便于判断是否总是同一个合约在卡 (固定卡 → 可能已下架/异常, 需单独处理)。
                log.error("Concurrent task timed out for symbol: {}", symbols.get(i));
            } catch (Exception e) {
                log.error("Concurrent task failed for symbol: {}", symbols.get(i), e);
            }
        }
        return results;
    }

    private String executeRequest(String url) {
        int maxRetries = 3;
        int[] backoffMs = {500, 1500, 3000};

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            // 进入请求前先看全局冷却: 别的线程刚被限流时, 本线程一起等, 不去火上浇油。
            awaitRateLimitCooldown();
            Request request = new Request.Builder().url(url).get().build();
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.code() == 429 || response.code() == 418) {
                    // 优先用币安返回的 Retry-After(秒), 否则退避表; 置全局冷却让所有线程一起退。
                    long backoff = retryAfterMs(response, backoffMs[Math.min(attempt, backoffMs.length - 1)]);
                    long until = System.currentTimeMillis() + backoff;
                    rateLimitedUntilMs.updateAndGet(prev -> Math.max(prev, until));
                    if (attempt < maxRetries) {
                        log.warn("Rate limited ({}), global backoff {}ms: {}", response.code(), backoff, url);
                        Thread.sleep(backoff);
                        continue;
                    }
                    log.error("Rate limited ({}) after {} retries, giving up: {}", response.code(), maxRetries, url);
                    return "";
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
            } catch (InterruptedIOException e) {
                // 超时类 (connect/read/call timeout): 不重试。重试会把单 symbol 耗时叠加到
                // maxRetries×callTimeout, 突破 executeConcurrent 的 120s 预算并拖死整轮。快速失败返回空。
                log.warn("Request timed out, no retry: {}", url);
                return "";
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

    /** 若处于全局限流冷却窗口内, 先睡到冷却结束(单次最多睡10s, 避免拖死单个请求的整体预算)。 */
    private void awaitRateLimitCooldown() {
        long wait = rateLimitedUntilMs.get() - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(Math.min(wait, 10_000L));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** 解析 429/418 响应的 Retry-After 头(秒), 返回退避毫秒数; 缺失或非法则用 fallback。上限30s。 */
    private long retryAfterMs(Response response, long fallbackMs) {
        String header = response.header("Retry-After");
        if (header != null) {
            try {
                return Math.min(Long.parseLong(header.trim()) * 1000L, 30_000L);
            } catch (NumberFormatException ignored) {
                // 非数字(理论上币安只发秒数), 退回 fallback
            }
        }
        return fallbackMs;
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

    private long periodToMillis(String period) {
        return switch (period) {
            case "5m" -> 300_000L;
            case "15m" -> 900_000L;
            case "30m" -> 1_800_000L;
            case "1h" -> 3_600_000L;
            case "2h" -> 7_200_000L;
            case "4h" -> 14_400_000L;
            case "6h" -> 21_600_000L;
            case "12h" -> 43_200_000L;
            case "1d" -> 86_400_000L;
            default -> 300_000L;
        };
    }
}
