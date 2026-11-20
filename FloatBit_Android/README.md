# FloatBit · 安卓版 ◈

轻量级加密货币行情 App，支持**桌面悬浮窗**。Kotlin + XML 布局，数据来自币安合约公共 WebSocket（与桌面版同源，无需密钥）。

## 功能

- 📊 **行情列表**：自选币种实时价格 + 24 小时涨跌幅
- ⚡ **WebSocket 实时**：`.com` / `.me` 合约通道自动选路，成交实时推送、50ms 合并显示，自动重连
- 🪟 **悬浮窗**：浮在其他 App 之上，可拖动，关闭按钮收起
- ➕ **增删币种**：输入框添加（只输币名自动补 USDT），点 ✕ 删除
- 🎨 **涨跌配色**：绿涨红跌 / 红涨绿跌 一键切换
- 💾 自选与配色本地保存

## 在 Android Studio 中运行

1. 用 Android Studio 打开 `FloatBit_Android` 文件夹
2. 等待 Gradle 同步（会自动下载 OkHttp、RecyclerView 等依赖）
3. 连接手机（开启 USB 调试）或启动模拟器
4. 点 ▶ Run

## 使用悬浮窗

1. 在 App 里点「开启悬浮窗」
2. 首次会跳转系统设置，打开「显示在其他应用上层」权限
3. 返回 App 再点一次「开启悬浮窗」，悬浮窗即出现
4. 按住悬浮窗可拖动；点 ✕ 关闭

## 主要文件

```
app/src/main/
├─ java/com/example/floatbit_android/
│  ├─ MainActivity.kt      # 主界面: 列表/增删/配色/悬浮窗开关
│  ├─ FloatingService.kt   # 悬浮窗前台服务 (WindowManager Overlay)
│  ├─ MarketClient.kt      # 币安 WebSocket 客户端 (OkHttp, 自动重连)
│  ├─ TickerAdapter.kt     # 行情列表适配器
│  ├─ Ticker.kt            # 行情数据模型 + 格式化
│  └─ Prefs.kt             # 轻量配置存储
└─ res/
   ├─ layout/              # activity_main / item_ticker / float_window
   ├─ drawable/            # 卡片/按钮/输入框背景
   └─ values/              # 颜色/字符串/主题
```

## 说明

- minSdk 24（Android 7.0）起；悬浮窗权限在 Android 6.0+ 需用户手动授予。
- 仅使用公共行情接口，不读取任何 API 密钥。
- iOS 不支持悬浮窗（系统限制），故本版本仅安卓。

## 1.2.0 网络与启动

默认自动直连，无需预先开启代理。移除页面恢复时的重复重连，首价由 WebSocket 或并行 REST 提供，图表独立加载。5 秒 REST 仅为断线/过期时备用。保留旧版紧凑浮窗。详见 [网络说明](../docs/android-network.md)。
