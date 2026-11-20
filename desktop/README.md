# FloatBit · 桌面版 ◈

实时合约行情悬浮窗。简约、置顶、随手拖动，盯盘不挡视线。

## 功能

- 🪟 **悬浮窗**：无边框、半透明、始终置顶，可自由拖动并记忆位置
- ⚡ **实时价格**：基于 WebSocket，币安行情变动即时推送，价格秒级跳动并高亮提示
- 📈 **走势小图**：每个币种迷你 K线 走势图，颜色跟随当日涨跌
- 🔍 **主面板**：搜索全部交易对、热门币种一键添加、自选管理
- 🎨 **主题与配色**：明暗双主题；绿涨红跌 / 红涨绿跌可切换
- 🟢 **迷你悬浮球**：点「—」收为桌面小球，单击展开，可拖动
- 🔔 **系统托盘**：显示/隐藏、调节透明度、退出
- 🔒 **无需密钥**：数据取自币安 USDⓈ-M 合约公共行情接口

## 技术栈

Electron 31 + 原生 HTML/CSS/JS，WebSocket 实时行情（`ws` 库），electron-builder 打包。

## 开发

```bash
cd desktop
npm ci
npm start
```

## 打包 Windows 安装程序

```bash
npm run dist
```

产物在 `desktop/dist/`。

## 目录

```
desktop/
├─ src/
│  ├─ main.js        # 主进程: 窗口/托盘/WebSocket 行情/REST/IPC
│  ├─ preload.js     # 安全桥接层
│  ├─ index.html + renderer.js + styles.css   # 悬浮窗
│  ├─ panel.html + panel.js + panel.css        # 主面板
│  └─ mini.html      # 迷你悬浮球
├─ assets/           # 图标 (ico/png/tray)
├─ build/make_icon.py# 图标生成脚本 (Python + Pillow)
└─ dist/             # 打包产物 (构建生成, 不纳入版本管理)
```

## 数据来源

币安 (Binance) USDⓈ-M 合约公共行情，免认证、行情专用域名。

**实时价格 — WebSocket（主）**
```
wss://fstream.binance.com/market/stream?streams=btcusdt@ticker/ethusdt@ticker
```
币安在价格变动时主动推送，悬浮窗与主面板实时跳动。断线自动重连。

**走势图 K线 / 交易对列表 / 启动初值 — REST（辅）**
```
GET https://fapi.binance.com/fapi/v1/klines?symbol=BTCUSDT&interval=1d&limit=30
GET https://fapi.binance.com/fapi/v1/exchangeInfo
GET https://fapi.binance.com/fapi/v1/ticker/24hr?symbol=BTCUSDT
```
启动时用 REST 拉一次做秒开初值；WebSocket 断线时作为兜底。

涨跌幅按当日 (UTC 自然日) 开盘价计算。

## 操作

- 拖动顶部栏或底部栏移动悬浮窗
- 右上角：⚙ 主面板 / — 收为桌面悬浮球 / ✕ 隐藏到托盘
- 迷你悬浮球：单击展开、按住拖动
- 托盘图标：单击显示/隐藏，右键菜单可打开主面板、调节置顶与透明度、退出

## 安全提示

本应用仅使用**公共行情接口**，不需要也不会读取任何 API Key / Secret。
