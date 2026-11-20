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

```text
wss://fstream.binance.com/market/stream?streams=muusdt@ticker/sndkusdt@ticker/skhynixusdt@ticker
```

合约代码转小写，流之间用 `/` 分隔。消息包装为 `{ "stream": "...", "data": { ... } }`，`data.e == "24hrTicker"` 时读取 `s`（合约）、`c`（最新价）、`P`（24 小时涨跌幅）等字段。订阅变化会替换旧连接；连接失败会自动重连，安卓与桌面均在启动时请求 REST 快照；桌面在断线或 15 秒未收到所选合约推送时以 REST 兜底。Android 1.1.1 在 WebSocket 断线时每 5 秒轮询，连接成功但 15 秒无行情时也获取 REST 快照。旧的 `/stream` 路径在本次实测中能握手却没有 ticker 推送，现已统一使用 `/market/stream`。

## 涨跌幅与使用限制

桌面端保留原来的 UTC 自然日涨跌幅计算：用当天日线开盘价计算 `(最新价 - 开盘价) / 开盘价 × 100`，失败时退回接口 24 小时涨跌幅。安卓和官网展示接口的 24 小时涨跌幅，两者时间窗口不同。

股票合约和代币化现货属于不同产品，价格与可交易时段也可能不同。列表、上市状态、价格和涨跌幅以实时合约接口为准。网络、区域限制或接口限流可能导致行情暂时无法显示。

官方文档：[USDⓈ-M 合约市场数据](https://developers.binance.com/docs/derivatives/usds-margined-futures/market-data/rest-api/Exchange-Information)。

## Android 1.1.1 的直连备用通道

Android 的合约列表、ticker 和 K 线统一使用 `ContractHttp`。主域名发生连接错误或 5xx 时，通过腾讯 DNSPod 的 HTTPS DNS 查询当前 `fapi.binance.com` CNAME，仅接受该名称对应的 `*.cloudfront.net` 分发；使用返回地址建立 HTTPS 连接，保留 `Host: fapi.binance.com`，保持正常证书校验。不会写死 CDN 地址，也不会请求现货行情。

成功通道按 DNS TTL 短期缓存并优先复用；每次请求最多两次连接尝试。无效合约、限流与访问受限等 HTTP 4xx 直接报告，不轮换域名重复请求。DNS 服务只收到币安域名查询，不收到合约代码、价格查询或任何账户信息。

这是对币安当前 CDN 配置的兼容，CDN 分发规则可能改变，不作为币安承诺的长期备用 API。若主接口和备用通道均不可达，用户可在「网络」中选择自己的 HTTP 代理；REST 和 WSS 使用同一配置。排查过程见 [安卓网络说明](android-network.md)。
