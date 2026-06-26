# ba-sync 数据采集规格 (Data Acquisition Spec)

- 日期: 2026-06-26
- 状态: 待评审
- 适用服务: ba-sync (只写 MySQL, 不做分析)

## 1. 背景与目标

ba-sync 的角色不变 —— **从币安拉数据、写入 MySQL**。本规格不改架构, 只回答一个问题:

> 以"将来要做的分析"为尺子, 应该爬哪些数据、存到哪、多久刷一次、留多久?

将来的分析分两类:
- **工具型(描述性)**: 合约近几天涨跌、全市场涨幅/成交额排行等通用指标。
- **策略型(信号性)**: 基于 OI 增量、成交量、主动买卖盘、资金费率、多空情绪等的组合信号(选币级)。

分析侧(ba-analysis)尚未建成, 但其所需数据由本服务负责备齐。分析粒度定位在 **选币级(日线 ~ 1h/4h)**, 不做分钟级择时 —— 据此 **5m 数据整体砍掉**。

## 2. 范围

本规格只定 **采什么 / 存哪张表 / 什么周期 / 刷新粒度 / 历史深度**。

**不在本规格内(下一阶段单独设计)**:
- 权重感知限速器(读 `X-MBX-USED-WEIGHT-1M` 主动节流)
- 各数据精确刷新 cadence 排期, 尤其多空比的"覆盖多少币 + 多久一次"(吃 `/futures/data/` 的 1000次/5min)

## 3. 币安两个独立限额(贯穿全文)

| 限额桶 | 范围 | 上限 |
|---|---|---|
| **权重** | `/fapi/*`(K线/费率/ticker/premium) | 2400 / 分钟(响应头 `X-MBX-USED-WEIGHT-1M`) |
| **次数** | `/futures/data/*`(OI / 多空比) | ~1000 次 / 5 分钟 |

`/fapi/v1/klines` 权重随 limit: ≤100→1, ≤500→2, ≤1000→5。批量 ticker 权重 40/次但**一次拿全市场**。

## 4. 数据集总览

| # | 数据 | 周期 | 端点 | 限额桶 | 存储表 |
|---|---|---|---|---|---|
| 1 | K线 | **1d / 1h / 4h** | `/fapi/v1/klines` | 权重 | `klines`(现有) |
| 2 | 持仓量 OI | **1d** | `futures/data/openInterestHist` | 次数 | `open_interest`(现有) |
| 3 | 资金费率 | 8h 结算 | `/fapi/v1/fundingRate` | 权重 | `funding_rate`(现有) |
| 4 | 24h 全市场行情 | 快照 | `/fapi/v1/ticker/24hr`(批量) | 权重 40×1 | `ticker_24h`(新) |
| 5 | 标记价/溢价 | 快照 | `/fapi/v1/premiumIndex`(批量) | 权重低×1 | `premium_index`(新) |
| 6 | 多空比 | 待定(默认 1h) | `futures/data/*LongShortRatio`、`takerlongshortRatio` | 次数 | `long_short_ratio`(新) |
| 7 | 合约列表 | 每日 | `/fapi/v1/exchangeInfo` | 权重 1 | `symbols`(新) + `symbols.json`(兜底) |

## 5. 逐数据规格

### 5.1 K线 (1d / 1h / 4h)
- **字段**: openTime, OHLC, volume, closeTime, quoteAssetVolume, numberOfTrades, takerBuyBase, takerBuyQuote(已有全字段, 含主动买盘 → 可算买卖压力)。
- **表**: `klines`(无需改结构, `interval` 已是列, 写入值由 `5m` 换成 `1h/4h`)。
- **刷新**: 历史只拉一次、永久缓存(upsert 永不删); 每轮只强刷"当前未收盘那根"(`limit=2` upsert, 权重 1)。
- **历史深度(首次 backfill)**: 1d 30 天 / 1h 30 天(≈720 根) / 4h 30 天(≈180 根); 之后随时间累积。

### 5.2 持仓量 OI (1d)
- **字段**: sumOpenInterest(张), sumOpenInterestValue(U), timestamp(对齐 UTC 日界, 实测值盘中不滚动)。
- **表**: `open_interest`(period 仅保留 `1d`)。
- **刷新**: 每日一次无条件拉 30 天 upsert(已在 SyncScheduler 落地); upsert 永不删 → 突破币安 30 天上限, 长期累积。

### 5.3 资金费率 (8h)
- **字段**: fundingRate, fundingTime。
- **表**: `funding_rate`。
- **刷新**: 周期性拉最近 N 条 upsert(每日 3 条, 拉 10~100 条覆盖足够窗口)。

### 5.4 24h 全市场行情(新)
- **端点**: `/fapi/v1/ticker/24hr` **不带 symbol** → 一次返回所有合约。
- **字段**: priceChangePercent, lastPrice, openPrice, highPrice, lowPrice, weightedAvgPrice, volume, quoteVolume, count, openTime, closeTime。
- **表**: `ticker_24h` —— **只存最新快照**(PK=symbol, upsert), 带 captured_at。直接喂"全市场涨幅/成交额排行"工具型分析。
- **刷新**: 每个短周期 1 次调用(便宜)。
- **历史**: 默认不留时间序列(如需"市场宽度历史"再改 PK 加时间戳, 留作扩展)。

### 5.5 标记价/溢价(新)
- **端点**: `/fapi/v1/premiumIndex`(批量) → 所有合约。
- **字段**: markPrice, indexPrice, estimatedSettlePrice, lastFundingRate, nextFundingTime, interestRate, time。
- **表**: `premium_index` —— 最新快照(PK=symbol, upsert)。
- **刷新**: 每个短周期 1 次。

### 5.6 多空比(新)
- **端点(4 种, 均 `futures/data/`, 逐 symbol)**:
  - `globalLongShortAccountRatio`(全市场账户多空比)
  - `topLongShortAccountRatio`(大户账户数多空比)
  - `topLongShortPositionRatio`(大户持仓多空比)
  - `takerlongshortRatio`(主动买卖量比)
- **字段**: longShortRatio, long/short(或 buyVol/sellVol), timestamp。
- **表**: `long_short_ratio`, 用 `ratio_type` 区分 4 种; PK=(symbol, ratio_type, period, timestamp); upsert 累积。
- **周期**: 默认 `1h`(与新的中周期粒度对齐; 币安仅留 30 天, upsert 后本地累积)。
- **刷新**: ⚠️ **逐 symbol × 4 端点会重打 `/futures/data/` 次数桶**, 覆盖范围与频率留待下一阶段(更新策略)排期, 本规格只定"要爬 + 表结构"。

### 5.7 合约列表 → `symbols` 表(新)
- **动机**: 分析侧读 MySQL, 不应依赖只有 ba-sync 才有的本地文件; 入库后可 JOIN 过滤(status)、用 `onboardDate` 算新币年龄(解决"新币填不满窗口"误判)。
- **字段**: symbol(PK), pair, contract_type, status, base_asset, quote_asset, price_precision, quantity_precision, onboard_date, delivery_date, updated_at。
- **刷新**: 每日 symbol-update 流程里 upsert; `symbols.json` 保留为离线兜底/种子。

## 6. 存储 Schema (DDL 草案)

> `klines` / `open_interest` / `funding_rate` 沿用现有结构, 不再重列。以下为新增表。

```sql
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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS long_short_ratio (
    symbol VARCHAR(20) NOT NULL,
    ratio_type VARCHAR(20) NOT NULL,   -- GLOBAL_ACCOUNT | TOP_ACCOUNT | TOP_POSITION | TAKER
    period VARCHAR(10) NOT NULL,
    `timestamp` BIGINT NOT NULL,
    long_short_ratio VARCHAR(30),
    long_value VARCHAR(30),            -- taker 类存 buyVol
    short_value VARCHAR(30),           -- taker 类存 sellVol
    PRIMARY KEY (symbol, ratio_type, period, `timestamp`),
    INDEX idx_lsr_lookup (symbol, ratio_type, period, `timestamp` DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

## 7. 移除项: 5m 全砍

| 移除 | 涉及代码 |
|---|---|
| 5m K线同步 + 强刷 | `SyncScheduler.syncShortTerm`、`DataSyncService.refreshCurrentKline("5m")` |
| 5m OI 同步 | `DataSyncService.syncIntradayOi`、`fetchOiHistoryByPeriod("5m")`、`INTRADAY_OI_PERIODS` |
| 5m 启动补数据 | `DataInitializer.backfillIntraday`、`backfillOiRange`、`IntradayInit` 配置 |
| 短线策略配置 | `AppProperties.ShortTermRiseConfig`(interval=5m) → 分析侧将来改 1h/4h 或弃用 |

**存量 5m 数据**: 库里已有的 `klines.interval='5m'` / `open_interest.period='5m'` 行可保留(无害)或一次性清理(可选)。

## 8. 限额预算(粗算, 验证可行性)

砍 5m 后稳态每"短周期"一轮:
- K线(1d/1h/4h): 历史命中缓存, 仅强刷当前根 → 3 interval × 逐 symbol × `limit=2`(权重1)。这是权重大头, 由下一阶段限速器按 `X-MBX-USED-WEIGHT-1M` 节流。
- ticker_24h: 1 次(权重40)。premium: 1 次。→ 几乎免费, 覆盖工具型全市场分析。
- OI 1d: 每日 1 次 backfill(不在短周期内)。
- 多空比: **唯一持续吃"次数桶"的项**, 必须限频/限范围 → 下一阶段定。

结论: 砍 5m + 历史永久缓存 + 批量端点后, **权重桶压力主要来自逐 symbol 强刷当前 K线**, 次数桶压力集中在多空比。两者都可控, 留给下一阶段的限速器与 cadence 设计。

## 9. 下一阶段(本规格定稿后)

1. **权重感知限速器**: 读 `X-MBX-USED-WEIGHT-1M` 主动节流 + 现有 429 全局冷却兜底 + backfill 摊开。
2. **刷新 cadence 排期**: 各数据精确频率; 重点是多空比的覆盖范围(全市场 vs 关注池)与频率。
3. **落地改造**: 按本规格增删表与同步任务(writing-plans 阶段产出实施计划)。
