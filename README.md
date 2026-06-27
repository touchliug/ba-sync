# ba-sync — 币安数据同步服务

从币安 USDT 永续合约 API 拉取数据并写入 MySQL。**只写库,不做分析。**

## 职责
- 更新合约列表(`/fapi/v1/exchangeInfo`)→ `symbols.json`
- 同步 K 线(1d / 1h / 4h)、持仓量 OI(1d + 1h)、资金费率、24h 行情、标记价/溢价、多空比到 MySQL
- 拥有数据库 schema:`JdbcDataStore` 启动时 `CREATE TABLE IF NOT EXISTS` 并做幂等迁移
- 日线 OI / K线 / 资金费率 / 多空比每次 upsert、永不删除 → 序列随时间无限累积,突破币安 `openInterestHist` 的 30 天上限
- 启动时做数据完整性校验并补齐缺口(见下方「启动初始化」)

## 启动初始化(DataInitializer,ApplicationReadyEvent)
系统就绪后跑一次,单一有序链路:
1. **先同步合约列表** → 从币安拉最新 USDT 永续合约 → 写回 `symbols.json` → 刷新 `SyncScheduler` 内存缓存(避免运行期任务沿用旧列表)
2. **逐 symbol 校验并补齐缺口**(只补真正缺数据的 symbol,不动已有历史):

| 数据 | 窗口 | 判缺方式 |
|---|---|---|
| 日 K 线(1d) | 30 天 | 记录数 < `days - tolerance`(默认容差 2 天) |
| 日线 OI(1d) | 30 天 | 同上 |
| 资金费率(8h 结算) | 30 天 | 记录数 < `(days-tolerance)*3` |
| 1h / 4h K 线 | 30 天 | 窗口内根数 < 期望 × 0.9(`days*24` / `days*6`) |
| 1h OI | 30 天 | 同上(分页拉取,币安单次上限 500) |

配置见 `application.yml` 的 `binance.init.*`。日 K 用币安原生 UTC 日界(北京 08:00 分界),均无时区误差。上市不足窗口的新币天然填不满,每次启动会被判缺重拉 —— 启动级一次性任务,可接受。

## 定时任务(application.yml `binance.schedule`,北京时间)
| 任务 | cron | 说明 |
|---|---|---|
| symbol-update | `0 0 6 * * ?` | 每天 6 点更新合约列表 |
| short-term-sync | `0 */3 * * * ?` | 每 3 分钟刷新 1d/1h/4h K线(含当天动态那根)+ 资金费率 + 24h 行情 + 标记价/溢价 |
| mid-freq-sync | `0 */30 * * * ?` | 每 30 分钟同步 1h OI + 多空比(吃 `/futures/data` 计次桶) |
| daily-oi-sync | `0 10 8 * * ?` | 每日同步日线 OI(累积长历史) |

## 数据库表结构
MySQL `ba` 库共 7 张表,字段含义、单位、来源端点、表间关系详见
👉 [docs/数据库表结构说明.md](docs/数据库表结构说明.md)

## 与 ba-analysis 的关系
两个服务**完全独立**,仅通过 MySQL 交换数据。本服务是唯一的写入方,`ba-analysis` 只读。
**首次部署必须先启动本服务**(它负责建表),再启动 `ba-analysis`。

## 运行
```bash
mvn package -DskipTests
./start.sh            # 后台启动; 端口 8081
./start.sh log        # 跟踪日志
./start.sh status     # 查看状态
```
需要能出网访问 `fapi.binance.com`(EC2 等环境靠 `-Djava.net.preferIPv4Stack=true` 避免 IPv6 卡死)。
