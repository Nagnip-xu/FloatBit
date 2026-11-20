# 币安 USDⓈ-M 合约行情接口

FloatBit 1.1.0 起，Windows、Android、官网演示与共享模块统一使用 **币安 USDⓈ-M 合约市场**，公共行情请求不需要 API 密钥。

## 数据范围与搜索

列表来自 `GET https://fapi.binance.com/fapi/v1/exchangeInfo`，仅展示 `status == TRADING` 的合约，同时保留普通永续、TradFi 股票合约等接口返回的合约类型。

- MU、SNDK、SKHYNIX 对应 `MUUSDT`、`SNDKUSDT`、`SKHYNIXUSDT`，来自该合约列表。
- 搜索按基础代码或完整交易对精确匹配优先，再按前缀和包含匹配排序。支持 `MU/USDT` 等输入。
- 桌面与安卓合约列表缓存 60 秒，支持前台自动更新与手动刷新。失败保留已有列表并显示重试提示。
- 旧现货自选中与合约同名的代码可继续查看合约行情；现货专有代码不会自动猜测映射。例如旧 `NVDABUSDT` 应移除后搜索添加 `NVDAUSDT`。

## REST 接口

基础地址：`https://fapi.binance.com/fapi/v1`

| 用途 | 接口 |
| --- | --- |
| 全部合约及交易状态 | `GET /exchangeInfo` |
| 单合约 24 小时行情 | `GET /ticker/24hr?symbol=MUUSDT` |
| 最新成交价 | `GET /ticker/price?symbol=MUUSDT` |
| 走势图 | `GET /klines?symbol=SKHYNIXUSDT&interval=15m&limit=30` |
| 当日开盘价 | `GET /klines?symbol=MUUSDT&interval=1d&limit=1` |

多合约行情逐个用 `symbol` 查询，不使用现货接口专有的批量 `symbols` 参数。官网对同一合约的短时间并发查询去重。

24 小时行情字段为 `symbol`、`lastPrice`、`openPrice`、`highPrice`、`lowPrice`、`priceChangePercent`。K 线每条为 `[开始时间, 开, 高, 低, 收, 量, ...]`，走势图取索引 4 的收盘价。

## WebSocket

1.2.0 同时尝试 `fstream.binance.com` 和 `fstream.binance.me` 的 `/market/stream`，以首条有效行情选路，关闭其他候选。例如：

```text
wss://fstream.binance.me/market/stream?streams=muusdt@aggTrade/muusdt@ticker/sndkusdt@aggTrade/sndkusdt@ticker
```

消息为 `{ "stream": "...", "data": { ... } }`。`aggTrade` 使用 `s`（合约）、`p`（成交价）、`T`（成交时间）；`24hrTicker` 使用 `c`、`o`、`h`、`l`、`C` 等。按交易所时间合并，慢 ticker 不会覆盖新成交，但仍可补统计。界面每 50ms 合并更新。

启动快照与 WebSocket 并行，不等待日线。连接故障自动重连，5 秒 REST 只用于补齐首值/断线/过期数据，不是实时价格的刷新周期。搜索列表每分钟刷新、迷你走势图每 30 秒刷新，与价格推送互不阻塞。

## 涨跌幅与使用限制

桌面端保留原来的 UTC 自然日涨跌幅计算：用当天日线开盘价计算 `(最新价 - 开盘价) / 开盘价 × 100`，失败时退回接口 24 小时涨跌幅。安卓和官网展示接口的 24 小时涨跌幅，两者时间窗口不同。

股票合约和代币化现货属于不同产品，价格与可交易时段也可能不同。列表、上市状态、价格和涨跌幅以实时合约接口为准。网络、区域限制或接口限流可能导致行情暂时无法显示。

官方文档：[USDⓈ-M 合约市场数据](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Exchange-Information)。

## 1.2.0 两端的 REST 直连备用通道

Android `ContractHttp` 与 Windows `ContractNetwork` 同时请求主域名、解析官方 CDN，最快成功者胜出并取消其他请求。DNSPod HTTPS DNS 只接收域名查询；仅接受 `fapi.binance.com` 的 CloudFront CNAME，按 TTL 缓存。TLS 验证 CDN 主机名，HTTP Host 保留币安合约域名。Windows 开启 gzip，避免完整合约列表传输阻塞。

每轮最多两个候选，无效合约/限流等 HTTP 4xx 不再启动重试循环。CDN 分发规则可能变化，不作为币安长期备用 API 的承诺。详细诊断和实测见 [网络说明](android-network.md)。
