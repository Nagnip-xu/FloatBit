# shared · 跨平台共享代码

这里放**与平台无关的纯逻辑**，桌面版 / 网页版 / 手机版都能复用，避免重复实现。

## 文件

- `binance.js` — 币安 USDⓈ-M 合约公共行情接口封装（REST：行情、K线、交易对）+ 价格/百分比格式化等工具函数

## 设计原则

- 不依赖 Electron、浏览器或某个框架的专有 API
- 网络请求通过参数 `fetchFn` 注入，默认用全局 `fetch`，方便不同环境替换
- 以后做手机版（React Native / Capacitor 等）时，行情逻辑直接复用这里，只需重写界面

> 说明：当前桌面版 `desktop/src/main.js` 使用自带实现——价格走 **WebSocket 实时推送**
> （`wss://fstream.binance.com`，用 `ws` 库 + 自动重连），K线/交易对/启动初值走 REST
> （Electron `net` 模块绕过 TLS 限制）。本模块目前封装的是 REST 部分，供网页版/手机版复用；
> 后续可在此补充 WebSocket 封装，统一各端实时行情逻辑。
