# ba-sync — 币安数据同步服务

从币安 USDT 永续合约 API 拉取数据并写入 MySQL。**只写库,不做分析。**

## 职责
- 更新合约列表(`/fapi/v1/exchangeInfo`)→ `symbols.json`
- 同步 K 线(日线 + 5m)、持仓量 OI(5m + 日线)、资金费率到 MySQL
- 拥有数据库 schema:`JdbcDataStore` 启动时 `CREATE TABLE IF NOT EXISTS` 并做幂等迁移
- 日线 OI 每日 upsert、永不删除 → 序列随时间无限累积,突破币安 `openInterestHist` 的 30 天上限

## 与 ba-analysis 的关系
两个服务**完全独立**,仅通过 MySQL 交换数据。本服务是唯一的写入方,`ba-analysis` 只读。
**首次部署必须先启动本服务**(它负责建表),再启动 `ba-analysis`。

## 定时任务(application.yml `binance.schedule`,北京时间)
| 任务 | cron | 说明 |
|---|---|---|
| symbol-update | `0 0 6 * * ?` | 每天 6 点更新合约列表 |
| daily-kline-sync | `0 37 * * * ?` | 每小时同步日 K 线 |
| short-term-sync | `0 */5 * * * ?` | 每 5 分钟刷新日K/5mK/5m OI/资金费率 |
| daily-oi-sync | `0 20 8 * * ?` | 每日同步日线 OI(累积长历史) |

## 运行
```bash
mvn package -DskipTests
./start.sh            # 后台启动; 端口 8081
./start.sh log        # 跟踪日志
./start.sh status     # 查看状态
```
需要能出网访问 `fapi.binance.com`(EC2 等环境靠 `-Djava.net.preferIPv4Stack=true` 避免 IPv6 卡死)。
