# 币安 (Binance) 公共行情接口速查

FloatBit 各端统一使用币安公共行情接口，**无需 API 密钥**。

桌面版采用 **WebSocket 实时推送（主）+ REST（辅）** 的混合方式：价格走 WebSocket，
K线走势图、交易对列表、启动初值走 REST。

## 域名

- REST 行情专用域名（推荐，免认证、速度快）：`https://data-api.binance.vision`
- WebSocket 行情专用域名：`wss://data-stream.binance.vision`
- 主域名：`https://api.binance.com` / `wss://stream.binance.com:9443`

> 注意：在部分网络环境下，主域名（`api.binance.com` / `stream.binance.com`）连接慢或被拦截，
> 经实测 `*.binance.vision` 系列域名连通性更好，FloatBit 统一使用该系列。

## WebSocket 实时行情 (价格)

```
wss://data-stream.binance.vision/stream?streams=btcusdt@ticker/ethusdt@ticker
```

- 多个流用 `/` 分隔，符号小写 + `@ticker`
- 推送消息结构：`{ "stream": "...", "data": { ... } }`
- `data` 为 `24hrTicker` 事件，关键字段：
  - `s` 交易对（大写，如 BTCUSDT）
  - `c` 最新价
  - `o` / `h` / `l` 开盘 / 最高 / 最低
  - `P` 24h 涨跌幅（百分比数值）
  - `E` 事件时间（毫秒）
- 注意：连接每 24 小时会被服务器断开一次，需自动重连。

## 常用 REST 接口

| 用途 | 接口 |
|------|------|
| 单/多个交易对 24h 行情 | `GET /api/v3/ticker/24hr?symbol=BTCUSDT` 或 `?symbols=["BTCUSDT","ETHUSDT"]` |
| 最新价格（轻量） | `GET /api/v3/ticker/price?symbol=BTCUSDT` |
| K线/蜡烛图 | `GET /api/v3/klines?symbol=BTCUSDT&interval=1d&limit=30` |
| 全部交易对 | `GET /api/v3/exchangeInfo` |

K线 interval：`1m 3m 5m 15m 30m 1h 2h 4h 6h 8h 12h 1d 3d 1w 1M`

## 24hr 行情关键字段 (REST)

- `lastPrice` 最新价
- `openPrice` 开盘价（24h 前）
- `highPrice` / `lowPrice` 24h 最高/最低
- `priceChangePercent` 24h 涨跌幅（百分比数值，如 `1.504`）

## K线 数据格式

每条为数组：`[开始时间, 开, 高, 低, 收, 量, ...]`，走势图取 index[4] 收盘价。

## 当日涨跌幅算法

FloatBit 显示的是「当天」涨跌，而非 24h 滚动：

```
当日涨跌% = (最新价 - 当日开盘价) / 当日开盘价 × 100
```

最新价来自 WebSocket 推送（`c`），当日开盘价取自日线 K线（`interval=1d`）并缓存。
注意日线按 **UTC 自然日**划分（UTC 00:00 = 北京时间 08:00）。

## CORS

REST 公共接口返回 `Access-Control-Allow-Origin: *`，浏览器可直接跨域请求（官网首屏实时行情即依赖此特性）。

## 注意

- 优先使用 `*.binance.vision` 系列域名（连通性更好）。
- REST 总体限频较宽松；WebSocket 为推送式，无需轮询。

> 官方文档：https://developers.binance.com/docs/binance-spot-api-docs/
