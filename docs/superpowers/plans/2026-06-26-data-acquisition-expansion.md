# ba-sync 数据采集扩展 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 按《数据采集规格》(2026-06-26) 扩展 ba-sync 采集面: K线改 1d/1h/4h、OI 加 1h、新增 24h行情/标记价/多空比、合约列表入库, 并砍掉全部 5m。

**Architecture:** 不改架构 (Spring Boot + OkHttp + JDBC + @Scheduled)。新增数据各自一张表 + 一个 model + BinanceClient 一个解析方法 + JdbcDataStore 一组 upsert/查询 + SyncScheduler 同步接线。本次首次引入测试: 解析层用 OkHttp MockWebServer, repo 层用本地 MySQL `ba_test` 库做往返测试 (无 Docker)。

**Tech Stack:** Java 21, Spring Boot 3.2.5, OkHttp 4.12, Jackson, MySQL; 测试 JUnit5 + AssertJ + okhttp mockwebserver。

---

## 前置约定 (所有任务通用)

- **提交策略**: 直接提交 `main` (用户既有约定), 每个任务末尾 commit。
- **编译**: `mvn -q -o compile`; **跑测试**: `mvn -q -o test`。
- **repo 测试数据库**: 本地 MySQL 建一个独立库 `ba_test`。测试基类从环境变量读连接, 缺省 `jdbc:mysql://127.0.0.1:3306/ba_test`、user=`root`、password 取环境变量 `DB_TEST_PASSWORD` (不硬编码进 git)。
  - 执行前一次性准备: 在本地 MySQL 执行 `CREATE DATABASE IF NOT EXISTS ba_test CHARACTER SET utf8mb4;` 并 `export DB_TEST_PASSWORD=...`。
- **解析测试**: 用 `MockWebServer` 起本地 HTTP, 把 `AppProperties.baseUrl` 指向它, 不连真币安。
- **不可单测的部分**: `SyncScheduler` / `DataInitializer` 这类纯 I/O 串联, 用"编译 + 实跑看日志/查库"验证, 计划里以 **[手工验证]** 标注, 不强行造测试。

---

## 文件结构 (本次涉及)

**新建**
- `model/Ticker24h.java`, `model/PremiumIndex.java`, `model/LongShortRatio.java`, `model/LongShortRatioRow.java`
- `client/LsrType.java` (多空比4端点枚举)
- `src/test/java/com/ba/analyzer/client/BinanceClientParseTest.java`
- `src/test/java/com/ba/analyzer/support/RepoTestBase.java`
- `src/test/java/com/ba/analyzer/service/JdbcDataStoreTest.java`

**修改**
- `pom.xml` (测试依赖)
- `client/BinanceClient.java` (新解析方法)
- `service/JdbcDataStore.java` (新表 DDL + 新 upsert/查询)
- `service/DataSyncService.java` (新同步方法 + 多空比映射 + 删 5m)
- `service/SymbolService.java` (symbol 入库)
- `scheduler/SyncScheduler.java` (1h/4h K线、1h OI、ticker/premium/lsr 接线、删 5m)
- `scheduler/DataInitializer.java` (1h/4h/1h-OI backfill、删 5m intraday)
- `config/AppProperties.java` (删 IntradayInit/shortTermRise, 加 lsr 配置)
- `src/main/resources/application.yml` + `config/application.yml.example` (周期/cron/删5m)

---

## Phase 0 — 测试基础设施

### Task 0.1: 加测试依赖

**Files:** Modify: `pom.xml`

- [ ] **Step 1: 加依赖**

在 `pom.xml` `<dependencies>` 末尾 (mysql 依赖之后) 加:

```xml
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
        <dependency>
            <groupId>com.squareup.okhttp3</groupId>
            <artifactId>mockwebserver</artifactId>
            <version>${okhttp.version}</version>
            <scope>test</scope>
        </dependency>
```

- [ ] **Step 2: 验证依赖解析**

Run: `mvn -q -o dependency:resolve`
Expected: 成功, 无报错 (依赖已在本地仓库则离线可用; 若缺失去掉 `-o` 联网拉一次)。

- [ ] **Step 3: Commit**

```bash
git add pom.xml
git commit -m "test: 引入 spring-boot-starter-test + okhttp mockwebserver"
```

### Task 0.2: 解析测试脚手架 (验证 MockWebServer 通路, 覆盖现有 getKlines)

**Files:** Create: `src/test/java/com/ba/analyzer/client/BinanceClientParseTest.java`

- [ ] **Step 1: 写测试**

```java
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
```

- [ ] **Step 2: 跑测试**

Run: `mvn -q -o test -Dtest=BinanceClientParseTest`
Expected: PASS (现有 getKlines + 之前加的空响应判空保证两个用例都过)。

- [ ] **Step 3: Commit**

```bash
git add src/test/java/com/ba/analyzer/client/BinanceClientParseTest.java
git commit -m "test: BinanceClient 解析测试脚手架 (MockWebServer)"
```

### Task 0.3: repo 测试基类 (验证本地 MySQL 通路, 覆盖现有 klines 往返)

**Files:**
- Create: `src/test/java/com/ba/analyzer/support/RepoTestBase.java`
- Create: `src/test/java/com/ba/analyzer/service/JdbcDataStoreTest.java`

- [ ] **Step 1: 写基类**

```java
package com.ba.analyzer.support;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

public final class RepoTestBase {
    private RepoTestBase() {}

    public static JdbcTemplate testJdbc() {
        String url = System.getenv().getOrDefault("DB_TEST_URL",
                "jdbc:mysql://127.0.0.1:3306/ba_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai");
        String user = System.getenv().getOrDefault("DB_TEST_USER", "root");
        String pass = System.getenv().getOrDefault("DB_TEST_PASSWORD", "");
        DriverManagerDataSource ds = new DriverManagerDataSource(url, user, pass);
        ds.setDriverClassName("com.mysql.cj.jdbc.Driver");
        return new JdbcTemplate(ds);
    }
}
```

- [ ] **Step 2: 写测试 (现有 klines 往返, 验证连库 + init 建表)**

```java
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
```

- [ ] **Step 3: 跑测试**

先确保 `ba_test` 库存在且 `DB_TEST_PASSWORD` 已导出。
Run: `mvn -q -o test -Dtest=JdbcDataStoreTest`
Expected: PASS。若报连接失败 → 检查 ba_test 库与 DB_TEST_PASSWORD。

- [ ] **Step 4: Commit**

```bash
git add src/test/java/com/ba/analyzer/support/RepoTestBase.java src/test/java/com/ba/analyzer/service/JdbcDataStoreTest.java
git commit -m "test: repo 测试基类 + klines 往返 (本地 ba_test 库)"
```

---

## Phase 1 — 合约列表入库 (symbols 表)

### Task 1.1: symbols 表 DDL

**Files:** Modify: `service/JdbcDataStore.java` (在 `init()` 内, `migrateOpenInterestPeriod()` 调用之前追加)

- [ ] **Step 1: 在 init() 里加建表**

在 `init()` 方法的 `migrateOpenInterestPeriod();` 这一行之前插入:

```java
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
```

- [ ] **Step 2: 编译**

Run: `mvn -q -o compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add src/main/java/com/ba/analyzer/service/JdbcDataStore.java
git commit -m "feat: symbols 表 DDL"
```

### Task 1.2: symbols upsert/查询 (TDD)

**Files:**
- Modify: `service/JdbcDataStore.java`
- Modify: `src/test/java/com/ba/analyzer/service/JdbcDataStoreTest.java`

- [ ] **Step 1: 写失败测试**

在 `JdbcDataStoreTest` 加:

```java
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
```

- [ ] **Step 2: 跑, 看失败**

Run: `mvn -q -o test -Dtest=JdbcDataStoreTest#saveAndGetSymbols_roundTrip`
Expected: 编译失败 (`saveSymbols`/`getSymbols` 未定义)。

- [ ] **Step 3: 实现**

在 `JdbcDataStore` 加 (在 Funding Rate 区块之后):

```java
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
```

- [ ] **Step 4: 跑, 看通过**

Run: `mvn -q -o test -Dtest=JdbcDataStoreTest`
Expected: PASS。

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/ba/analyzer/service/JdbcDataStore.java src/test/java/com/ba/analyzer/service/JdbcDataStoreTest.java
git commit -m "feat: symbols upsert/查询 + 往返测试"
```

### Task 1.3: symbol 同步时写库 [手工验证]

**Files:** Modify: `service/SymbolService.java`

- [ ] **Step 1: 注入 dataStore 并在保存时入库**

`SymbolService` 加构造注入 `JdbcDataStore dataStore` (加到现有构造参数末尾并赋值), 然后在 `fetchAndSaveSymbols()` 的 `saveSymbolsToFile(symbols);` 之后加一行:

```java
        try {
            dataStore.saveSymbols(symbols);
            log.info("Upserted {} symbols to DB", symbols.size());
        } catch (Exception e) {
            log.error("Failed to upsert symbols to DB (file still saved)", e);
        }
```

- [ ] **Step 2: 编译**

Run: `mvn -q -o compile`
Expected: BUILD SUCCESS。

- [ ] **Step 3: [手工验证]** 实跑一次, 触发 symbol-update 或启动, 查 `SELECT COUNT(*) FROM symbols;` 应有数百行。

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/ba/analyzer/service/SymbolService.java
git commit -m "feat: 合约列表同步时 upsert 入库 (文件保留兜底)"
```

---

## Phase 2 — 24h 全市场行情 (ticker_24h)

### Task 2.1: Ticker24h model

**Files:** Create: `model/Ticker24h.java`

- [ ] **Step 1: 建 model**

```java
package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Ticker24h {
    private String symbol;
    private String priceChange;
    private String priceChangePercent;
    private String weightedAvgPrice;
    private String lastPrice;
    private String openPrice;
    private String highPrice;
    private String lowPrice;
    private String volume;
    private String quoteVolume;
    private long openTime;
    private long closeTime;
    private long count;
}
```

- [ ] **Step 2: 编译 + Commit**

Run: `mvn -q -o compile` → SUCCESS
```bash
git add src/main/java/com/ba/analyzer/model/Ticker24h.java
git commit -m "feat: Ticker24h model"
```

### Task 2.2: get24hrTickers 解析 (TDD)

**Files:**
- Modify: `client/BinanceClient.java`
- Modify: `src/test/java/com/ba/analyzer/client/BinanceClientParseTest.java`

- [ ] **Step 1: 写失败测试**

```java
    @Test
    void get24hrTickers_parsesArray() {
        server.enqueue(new MockResponse().setBody(
                "[{\"symbol\":\"BTCUSDT\",\"priceChangePercent\":\"2.5\",\"lastPrice\":\"60000\"," +
                "\"quoteVolume\":\"123\",\"openTime\":1,\"closeTime\":2,\"count\":9}]"));

        var tickers = client.get24hrTickers();

        assertThat(tickers).hasSize(1);
        assertThat(tickers.get(0).getSymbol()).isEqualTo("BTCUSDT");
        assertThat(tickers.get(0).getPriceChangePercent()).isEqualTo("2.5");
        assertThat(tickers.get(0).getCount()).isEqualTo(9);
    }
```

- [ ] **Step 2: 跑, 看失败** — Run: `mvn -q -o test -Dtest=BinanceClientParseTest#get24hrTickers_parsesArray` → 编译失败 (方法未定义)。

- [ ] **Step 3: 实现** — 在 `BinanceClient` 加 (import `com.ba.analyzer.model.Ticker24h`):

```java
    public List<Ticker24h> get24hrTickers() {
        String url = UriComponentsBuilder.fromHttpUrl(appProperties.getBaseUrl())
                .path("/fapi/v1/ticker/24hr").build().toUriString();
        String json = executeRequest(url);
        if (json.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Ticker24h>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse 24hr tickers", e);
            return Collections.emptyList();
        }
    }
```

- [ ] **Step 4: 跑, 看通过** — Run: `mvn -q -o test -Dtest=BinanceClientParseTest` → PASS。

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/ba/analyzer/client/BinanceClient.java src/test/java/com/ba/analyzer/client/BinanceClientParseTest.java
git commit -m "feat: get24hrTickers 批量行情解析 + 测试"
```

### Task 2.3: ticker_24h 表 + upsert/查询 (TDD)

**Files:** Modify: `service/JdbcDataStore.java`, `JdbcDataStoreTest.java`

- [ ] **Step 1: 加表 DDL** — 在 `init()` 内 symbols 建表之后加:

```java
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
```

- [ ] **Step 2: 写失败测试**

```java
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
```

- [ ] **Step 3: 跑看失败** — `mvn -q -o test -Dtest=JdbcDataStoreTest#saveAndGetTickers_roundTrip` → 编译失败。

- [ ] **Step 4: 实现** — 加:

```java
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
```

- [ ] **Step 5: 跑看通过 + Commit**

Run: `mvn -q -o test -Dtest=JdbcDataStoreTest` → PASS
```bash
git add src/main/java/com/ba/analyzer/service/JdbcDataStore.java src/test/java/com/ba/analyzer/service/JdbcDataStoreTest.java
git commit -m "feat: ticker_24h 表 + upsert/查询 + 测试"
```

### Task 2.4: ticker 同步方法 + 接线 [手工验证]

**Files:** Modify: `service/DataSyncService.java`, `scheduler/SyncScheduler.java`

- [ ] **Step 1: DataSyncService 加同步方法**

```java
    /** 拉全市场24h行情(单次批量)并 upsert 最新快照。 */
    public void syncTickers() {
        List<com.ba.analyzer.model.Ticker24h> tickers = binanceClient.get24hrTickers();
        if (!tickers.isEmpty()) {
            dataStore.saveTickers(tickers);
            log.info("Synced {} 24h tickers", tickers.size());
        }
    }
```

- [ ] **Step 2: SyncScheduler.syncShortTerm 内接线** — 在 `syncFundingRates(symbols)` 之后加 `dataSyncService.syncTickers();`

- [ ] **Step 3: 编译** → `mvn -q -o compile` SUCCESS

- [ ] **Step 4: [手工验证]** 实跑一轮, 查 `SELECT COUNT(*) FROM ticker_24h;` 应≈全市场合约数。

- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/ba/analyzer/service/DataSyncService.java src/main/java/com/ba/analyzer/scheduler/SyncScheduler.java
git commit -m "feat: 24h行情同步接入短周期任务"
```

---

## Phase 3 — 标记价/溢价 (premium_index)

### Task 3.1: PremiumIndex model

**Files:** Create: `model/PremiumIndex.java`

- [ ] **Step 1: 建 model**

```java
package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PremiumIndex {
    private String symbol;
    private String markPrice;
    private String indexPrice;
    private String estimatedSettlePrice;
    private String lastFundingRate;
    private long nextFundingTime;
    private String interestRate;
    private long time;
}
```

- [ ] **Step 2: 编译 + Commit** — `mvn -q -o compile` →
```bash
git add src/main/java/com/ba/analyzer/model/PremiumIndex.java
git commit -m "feat: PremiumIndex model"
```

### Task 3.2: getPremiumIndexes 解析 (TDD)

**Files:** Modify: `client/BinanceClient.java`, `BinanceClientParseTest.java`

- [ ] **Step 1: 写失败测试**

```java
    @Test
    void getPremiumIndexes_parsesArray() {
        server.enqueue(new MockResponse().setBody(
                "[{\"symbol\":\"BTCUSDT\",\"markPrice\":\"60000\",\"indexPrice\":\"59990\"," +
                "\"lastFundingRate\":\"0.0001\",\"nextFundingTime\":123,\"time\":99}]"));

        var list = client.getPremiumIndexes();

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getMarkPrice()).isEqualTo("60000");
        assertThat(list.get(0).getNextFundingTime()).isEqualTo(123);
    }
```

- [ ] **Step 2: 跑看失败** → 编译失败。

- [ ] **Step 3: 实现** (import `PremiumIndex`):

```java
    public List<PremiumIndex> getPremiumIndexes() {
        String url = UriComponentsBuilder.fromHttpUrl(appProperties.getBaseUrl())
                .path("/fapi/v1/premiumIndex").build().toUriString();
        String json = executeRequest(url);
        if (json.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<PremiumIndex>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse premium index", e);
            return Collections.emptyList();
        }
    }
```

- [ ] **Step 4: 跑看通过 + Commit**
Run: `mvn -q -o test -Dtest=BinanceClientParseTest` → PASS
```bash
git add src/main/java/com/ba/analyzer/client/BinanceClient.java src/test/java/com/ba/analyzer/client/BinanceClientParseTest.java
git commit -m "feat: getPremiumIndexes 解析 + 测试"
```

### Task 3.3: premium_index 表 + upsert/查询 (TDD)

**Files:** Modify: `service/JdbcDataStore.java`, `JdbcDataStoreTest.java`

- [ ] **Step 1: 加表 DDL** — `init()` 内追加:

```java
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
```

- [ ] **Step 2: 写失败测试**

```java
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
```

- [ ] **Step 3: 跑看失败** → 编译失败。

- [ ] **Step 4: 实现**

```java
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
```

- [ ] **Step 5: 跑看通过 + Commit**
Run: `mvn -q -o test -Dtest=JdbcDataStoreTest` → PASS
```bash
git add src/main/java/com/ba/analyzer/service/JdbcDataStore.java src/test/java/com/ba/analyzer/service/JdbcDataStoreTest.java
git commit -m "feat: premium_index 表 + upsert/查询 + 测试"
```

### Task 3.4: premium 同步 + 接线 [手工验证]

**Files:** Modify: `service/DataSyncService.java`, `scheduler/SyncScheduler.java`

- [ ] **Step 1: DataSyncService 加方法**

```java
    /** 拉全市场标记价/溢价(单次批量)并 upsert 最新快照。 */
    public void syncPremiumIndex() {
        List<com.ba.analyzer.model.PremiumIndex> list = binanceClient.getPremiumIndexes();
        if (!list.isEmpty()) {
            dataStore.savePremiumIndexes(list);
            log.info("Synced {} premium index", list.size());
        }
    }
```

- [ ] **Step 2: SyncScheduler.syncShortTerm 内**, `syncTickers()` 之后加 `dataSyncService.syncPremiumIndex();`

- [ ] **Step 3: 编译** → SUCCESS
- [ ] **Step 4: [手工验证]** `SELECT COUNT(*) FROM premium_index;`
- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/ba/analyzer/service/DataSyncService.java src/main/java/com/ba/analyzer/scheduler/SyncScheduler.java
git commit -m "feat: 标记价/溢价同步接入短周期任务"
```

---

## Phase 4 — 多空比 (long_short_ratio)

### Task 4.1: LsrType 枚举 + model + row

**Files:** Create `client/LsrType.java`, `model/LongShortRatio.java`, `model/LongShortRatioRow.java`

- [ ] **Step 1: LsrType**

```java
package com.ba.analyzer.client;

public enum LsrType {
    GLOBAL_ACCOUNT("/futures/data/globalLongShortAccountRatio", false),
    TOP_ACCOUNT("/futures/data/topLongShortAccountRatio", false),
    TOP_POSITION("/futures/data/topLongShortPositionRatio", false),
    TAKER("/futures/data/takerlongshortRatio", true);

    private final String path;
    private final boolean taker;

    LsrType(String path, boolean taker) { this.path = path; this.taker = taker; }
    public String getPath() { return path; }
    public boolean isTaker() { return taker; }
    public String dbValue() { return name(); }
}
```

- [ ] **Step 2: LongShortRatio (原始解析, 兼容4端点字段)**

```java
package com.ba.analyzer.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class LongShortRatio {
    private String symbol;
    // 账户/持仓类
    private String longShortRatio;
    private String longAccount;
    private String shortAccount;
    // taker 类
    private String buySellRatio;
    private String buyVol;
    private String sellVol;
    private long timestamp;
}
```

- [ ] **Step 3: LongShortRatioRow (归一化入库形)**

```java
package com.ba.analyzer.model;

public record LongShortRatioRow(String symbol, String ratioType, String period, long timestamp,
                                String ratio, String longValue, String shortValue) {}
```

- [ ] **Step 4: 编译 + Commit** — `mvn -q -o compile` →
```bash
git add src/main/java/com/ba/analyzer/client/LsrType.java src/main/java/com/ba/analyzer/model/LongShortRatio.java src/main/java/com/ba/analyzer/model/LongShortRatioRow.java
git commit -m "feat: 多空比 LsrType 枚举 + model + 归一化 row"
```

### Task 4.2: getLongShortRatio 解析 (TDD)

**Files:** Modify: `client/BinanceClient.java`, `BinanceClientParseTest.java`

- [ ] **Step 1: 写失败测试 (账户类 + taker类各一)**

```java
    @Test
    void getLongShortRatio_account_parses() {
        server.enqueue(new MockResponse().setBody(
                "[{\"symbol\":\"BTCUSDT\",\"longShortRatio\":\"1.8\",\"longAccount\":\"0.64\"," +
                "\"shortAccount\":\"0.36\",\"timestamp\":111}]"));

        var list = client.getLongShortRatio("BTCUSDT", LsrType.GLOBAL_ACCOUNT, "1h", 30);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getLongShortRatio()).isEqualTo("1.8");
        assertThat(list.get(0).getLongAccount()).isEqualTo("0.64");
        assertThat(list.get(0).getTimestamp()).isEqualTo(111);
    }

    @Test
    void getLongShortRatio_taker_parses() {
        server.enqueue(new MockResponse().setBody(
                "[{\"buySellRatio\":\"1.2\",\"buyVol\":\"100\",\"sellVol\":\"83\",\"timestamp\":222}]"));

        var list = client.getLongShortRatio("BTCUSDT", LsrType.TAKER, "1h", 30);

        assertThat(list).hasSize(1);
        assertThat(list.get(0).getBuySellRatio()).isEqualTo("1.2");
        assertThat(list.get(0).getBuyVol()).isEqualTo("100");
    }
```

- [ ] **Step 2: 跑看失败** → 编译失败。

- [ ] **Step 3: 实现** (import `LongShortRatio`):

```java
    public List<LongShortRatio> getLongShortRatio(String symbol, LsrType type, String period, int limit) {
        String url = UriComponentsBuilder.fromHttpUrl(appProperties.getBaseUrl())
                .path(type.getPath())
                .queryParam("symbol", symbol)
                .queryParam("period", period)
                .queryParam("limit", limit)
                .build().toUriString();
        String json = executeRequest(url);
        if (json.isEmpty()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<LongShortRatio>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse long/short ratio ({}) for {}", type, symbol, e);
            return Collections.emptyList();
        }
    }
```

- [ ] **Step 4: 跑看通过 + Commit**
Run: `mvn -q -o test -Dtest=BinanceClientParseTest` → PASS
```bash
git add src/main/java/com/ba/analyzer/client/BinanceClient.java src/test/java/com/ba/analyzer/client/BinanceClientParseTest.java
git commit -m "feat: getLongShortRatio 解析 (账户/taker) + 测试"
```

### Task 4.3: long_short_ratio 表 + upsert/查询 (TDD)

**Files:** Modify: `service/JdbcDataStore.java`, `JdbcDataStoreTest.java`

- [ ] **Step 1: 加表 DDL** — `init()` 内追加:

```java
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
```

- [ ] **Step 2: 写失败测试**

```java
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
```

- [ ] **Step 3: 跑看失败** → 编译失败。

- [ ] **Step 4: 实现**

```java
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
```

- [ ] **Step 5: 跑看通过 + Commit**
Run: `mvn -q -o test -Dtest=JdbcDataStoreTest` → PASS
```bash
git add src/main/java/com/ba/analyzer/service/JdbcDataStore.java src/test/java/com/ba/analyzer/service/JdbcDataStoreTest.java
git commit -m "feat: long_short_ratio 表 + upsert/查询 + 测试"
```

### Task 4.4: 多空比同步 (模型→row 归一化) [手工验证]

**Files:** Modify: `config/AppProperties.java`, `service/DataSyncService.java`, `scheduler/SyncScheduler.java`

- [ ] **Step 1: AppProperties 加 lsr 配置** — 在 `InitConfig` 之后加内部类并加字段 `private LsrConfig lsr = new LsrConfig();`:

```java
    @Data
    public static class LsrConfig {
        private boolean enabled = true;
        private String period = "1h";
        private int limit = 30;
    }
```

- [ ] **Step 2: DataSyncService 加同步方法 (含 taker/账户类字段归一化)**

```java
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
```

- [ ] **Step 3: 编译** → SUCCESS (接线在 Phase 6 的中频任务里一起做, 见 Task 6.2)

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/ba/analyzer/config/AppProperties.java src/main/java/com/ba/analyzer/service/DataSyncService.java
git commit -m "feat: 多空比同步方法(4端点归一化) + lsr配置"
```

---

## Phase 5 — K线改 1d/1h/4h (砍 5m)

### Task 5.1: 短周期 K线同步改 1h/4h [手工验证]

**Files:** Modify: `scheduler/SyncScheduler.java`

- [ ] **Step 1: 改 syncShortTerm 的 K线部分**

把现有 `syncShortTerm` 里这段:
```java
            var stCfg = appProperties.getAnalysis().getShortTermRise();
            if (stCfg.isEnabled()) {
                int shortPeriod = stCfg.getPeriod() + appProperties.getConcurrency().getHistoryBufferDays();
                dataSyncService.fetchKlinesByInterval(symbols, stCfg.getInterval(), shortPeriod);
                dataSyncService.refreshCurrentKline(symbols, stCfg.getInterval());
            }
            dataSyncService.syncIntradayOi(symbols, INTRADAY_OI_PERIODS);
```
替换为 (1h/4h 历史走缓存 + 强刷当前根; 5m/intradayOi 删除):
```java
            for (String interval : INTRADAY_INTERVALS) {
                dataSyncService.fetchKlinesByInterval(symbols, interval, KLINE_HISTORY_BARS);
                dataSyncService.refreshCurrentKline(symbols, interval);
            }
```
并在类顶部常量区把 `INTRADAY_OI_PERIODS` 替换为:
```java
    /** 中短周期K线集合 (1d 在上面单独处理)。 */
    private static final List<String> INTRADAY_INTERVALS = List.of("1h", "4h");
    /** 1h/4h 历史缓存判定的回看根数 (30天 1h≈720; 取整 800 留余量, 仍 < 币安1500上限)。 */
    private static final int KLINE_HISTORY_BARS = 800;
```

- [ ] **Step 2: 编译** → 若 `INTRADAY_OI_PERIODS` 仍被引用会报错, 确保已全部替换。`mvn -q -o compile` SUCCESS。

- [ ] **Step 3: [手工验证]** 实跑一轮, 查 `SELECT DISTINCT \`interval\` FROM klines;` 应出现 1h/4h, 不再新增 5m。

- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/ba/analyzer/scheduler/SyncScheduler.java
git commit -m "feat: 短周期K线同步改为1h/4h(替换5m)"
```

### Task 5.2: 启动 backfill 改 1h/4h [手工验证]

**Files:** Modify: `scheduler/DataInitializer.java`

- [ ] **Step 1: 替换 backfillIntraday**

把 `verifyAndBackfill()` 里 `backfillIntraday(symbols, cfg.getIntraday(), now);` 这行删除, 改为对 1h/4h 各做按天数判缺补齐。在 `backfillDaily(...)` 调用之后加:
```java
        backfillMidIntervals(symbols, cfg.getDaily().getDays(), now);
```
并新增方法 (替换原 `backfillIntraday`):
```java
    /** 1h/4h K线: 按"窗口内根数 < 期望*容差"判缺并补。 */
    private void backfillMidIntervals(List<String> symbols, int days, long now) {
        record Plan(String interval, int barsPerDay) {}
        List<Plan> plans = List.of(new Plan("1h", 24), new Plan("4h", 6));
        long sinceMs = now - (long) days * DAY_MS;
        for (Plan p : plans) {
            int expected = days * p.barsPerDay();
            int minBars = (int) (expected * 0.9);
            List<String> miss = findMissing(symbols, dataStore.countKlinesSince(p.interval(), sinceMs), minBars);
            log.info("Mid-interval gap — {} klines:{} (window {}d, need {}/{})",
                    p.interval(), miss.size(), days, minBars, expected);
            dataSyncService.backfillKlines(miss, p.interval(), expected + p.barsPerDay());
        }
    }
```

- [ ] **Step 2: 编译** → SUCCESS
- [ ] **Step 3: [手工验证]** 删库 1h 数据后重启, 看日志 `Mid-interval gap — 1h ...` 并补齐。
- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/ba/analyzer/scheduler/DataInitializer.java
git commit -m "feat: 启动backfill 1h/4h K线(替换5m intraday)"
```

---

## Phase 6 — OI 加 1h + 中频任务接线

### Task 6.1: 1h OI 启动 backfill (分页) [手工验证]

**Files:** Modify: `scheduler/DataInitializer.java`

- [ ] **Step 1: 在 backfillMidIntervals 之后加 1h OI 补齐**

`verifyAndBackfill()` 内 `backfillMidIntervals(...)` 之后加:
```java
        backfillHourlyOi(symbols, cfg.getDaily().getDays(), now);
```
新增方法 (复用分页区间拉取):
```java
    /** 1h OI: 按窗口内点数判缺, 分页补 (单次上限500)。 */
    private void backfillHourlyOi(List<String> symbols, int days, long now) {
        long sinceMs = now - (long) days * DAY_MS;
        int expected = days * 24;
        int minPts = (int) (expected * 0.9);
        List<String> miss = findMissing(symbols, dataStore.countOpenInterestSince("1h", sinceMs), minPts);
        log.info("Hourly OI gap — {} symbols (window {}d, need {}/{})", miss.size(), days, minPts, expected);
        dataSyncService.backfillOiRange(miss, "1h", sinceMs, now);
    }
```

- [ ] **Step 2: 编译** → SUCCESS
- [ ] **Step 3: [手工验证]** 重启, 查 `SELECT COUNT(*) FROM open_interest WHERE period='1h';` 应≈ days×24×symbol数。
- [ ] **Step 4: Commit**
```bash
git add src/main/java/com/ba/analyzer/scheduler/DataInitializer.java
git commit -m "feat: 启动backfill 1h OI(分页区间拉取)"
```

### Task 6.2: 中频任务 (1h OI + 多空比), 30分钟一轮 [手工验证]

**Files:** Modify: `config/AppProperties.java`(已加 lsr), `src/main/resources/application.yml`, `scheduler/SyncScheduler.java`

- [ ] **Step 1: application.yml 的 schedule 加 cron**

在 `binance.schedule` 下加:
```yaml
    # 中频: 1h OI + 多空比 (吃 /futures/data 次数桶, 故 30 分钟一轮, 不进高频)
    mid-freq-sync: "0 */30 * * * ?"
```

- [ ] **Step 2: SyncScheduler 加中频任务**

```java
    @Scheduled(cron = "${binance.schedule.mid-freq-sync}")
    public void syncMidFreq() {
        try {
            List<String> symbols = getSymbols();
            log.info("Mid-freq sync: 1h OI + long/short ratio, {} symbols", symbols.size());
            // 1h OI: 拉最近若干点 upsert 累积 (单次上限500, 取200≈8天覆盖更新窗口足够)。
            dataSyncService.fetchOiHistoryByPeriod(symbols, "1h", 200);
            var lsr = appProperties.getLsr();
            if (lsr.isEnabled()) {
                dataSyncService.syncLongShortRatio(symbols, lsr.getPeriod(), lsr.getLimit());
            }
        } catch (Exception e) {
            log.error("Mid-freq sync failed", e);
        }
    }
```

- [ ] **Step 3: 编译** → SUCCESS
- [ ] **Step 4: [手工验证]** 等一轮(或临时改 cron 为 `0 */1`)看日志 `Mid-freq sync ...`, 查 `open_interest` period='1h' 与 `long_short_ratio` 有新数据。
- [ ] **Step 5: Commit**
```bash
git add src/main/java/com/ba/analyzer/scheduler/SyncScheduler.java src/main/resources/application.yml
git commit -m "feat: 中频任务 1h OI + 多空比(30min一轮)"
```

---

## Phase 7 — 移除 5m 残留 + 配置清理

### Task 7.1: 删 DataSyncService 的 5m 专用方法

**Files:** Modify: `service/DataSyncService.java`

- [ ] **Step 1: 删除 `syncIntradayOi(...)` 方法** (整段, 含 javadoc)。`backfillOiRange` 保留 (1h OI 仍用)。`refreshCurrentKline` 保留 (1h/4h 仍用)。

- [ ] **Step 2: 编译** — 若 `syncIntradayOi` 仍被引用应已在 Task 5.1 删除; `mvn -q -o compile` SUCCESS。

- [ ] **Step 3: Commit**
```bash
git add src/main/java/com/ba/analyzer/service/DataSyncService.java
git commit -m "refactor: 删除 5m 专用 syncIntradayOi"
```

### Task 7.2: 删 AppProperties 的 IntradayInit + ShortTermRiseConfig

**Files:** Modify: `config/AppProperties.java`

- [ ] **Step 1: 删除** `InitConfig` 内 `private IntradayInit intraday = ...;` 字段 + `IntradayInit` 内部类; 删除 `AnalysisConfig` 内 `private ShortTermRiseConfig shortTermRise = ...;` 字段 + `ShortTermRiseConfig` 内部类。

- [ ] **Step 2: 检查引用** — `DataInitializer.backfillIntraday` 已在 Task 5.2 删、`SyncScheduler` shortTermRise 已在 Task 5.1 删。`mvn -q -o compile` SUCCESS。

- [ ] **Step 3: Commit**
```bash
git add src/main/java/com/ba/analyzer/config/AppProperties.java
git commit -m "refactor: 删除 IntradayInit / ShortTermRiseConfig 死配置"
```

### Task 7.3: 清理 application.yml + example 的 5m 配置

**Files:** Modify: `src/main/resources/application.yml`, `config/application.yml.example`

- [ ] **Step 1: application.yml** — 删除 `binance.init.intraday` 整块、删除 `binance.analysis.short-term-rise` 整块 (`analysis` 下若空则连 `analysis:` 一起删)。加 lsr 默认 (可选, 有代码默认值):
```yaml
  lsr:
    enabled: true
    period: 1h
    limit: 30
```

- [ ] **Step 2: config/application.yml.example** — 在 `binance:` 下补一句注释说明新增 `mid-freq-sync` 与 `lsr` 可在此覆盖 (示例值, 不含密钥)。

- [ ] **Step 3: 编译打包验证 yml 合法** — `mvn -q -o package -DskipTests` SUCCESS。

- [ ] **Step 4: Commit**
```bash
git add src/main/resources/application.yml config/application.yml.example
git commit -m "chore: 清理 5m 相关配置, 加 lsr/mid-freq 配置"
```

### Task 7.4: (可选) 清理存量 5m 数据 [手工]

- [ ] **Step 1:** 确认无误后, 在生产库手工执行一次 (不写进代码):
```sql
DELETE FROM klines WHERE `interval`='5m';
DELETE FROM open_interest WHERE period='5m';
```
(保留亦无害; 仅为省空间。)

---

## 收尾验证

- [ ] **全量测试**: `mvn -q -o test` 全绿 (BinanceClientParseTest + JdbcDataStoreTest)。
- [ ] **打包**: `mvn -q -o package -DskipTests` SUCCESS。
- [ ] **[手工] 实跑一轮**, 确认日志: `Synced N 24h tickers`、`Synced N premium index`、`Mid-freq sync ...`、`DB hit for 1h klines`(缓存生效); 查库 7 张表均有数据 (klines 1d/1h/4h、open_interest 1d/1h、funding_rate、symbols、ticker_24h、premium_index、long_short_ratio)。
- [ ] **推送**: 走本地 7897 代理 `git -c http.proxy=http://127.0.0.1:7897 push origin main`。

---

## Self-Review 备注 (规格覆盖)

| 规格条目 | 对应任务 |
|---|---|
| K线 1d/1h/4h | Task 5.1 / 5.2 (1d 同步既有) |
| 成交量随K线 | 既有字段, 无需新任务 |
| OI 1d + 1h | 1d 既有; 1h: Task 6.1 / 6.2 |
| 资金费率 | 既有, 不变 |
| 24h 行情 | Phase 2 |
| 标记价/溢价 | Phase 3 |
| 多空比 4类 | Phase 4 + Task 6.2 接线 |
| symbols 入库 | Phase 1 |
| 删 5m | Phase 5 + Phase 7 |

**下一阶段 (不在本计划)**: 权重感知限速器 (读 `X-MBX-USED-WEIGHT-1M`)、各任务精确 cadence 调优、多空比覆盖范围(全市场 vs 关注池)。
