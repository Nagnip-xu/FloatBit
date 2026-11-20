# mobile · 手机版

FloatBit 安卓版项目位于仓库根目录的 **`FloatBit_Android/`** 文件夹（Kotlin + XML 布局）。

> 注：原计划将其移动到 `mobile/android/`，但 Android Studio 打开项目时会锁定文件。
> 如需归位，请先在 Android Studio 关闭项目，再手动把 `FloatBit_Android` 移到此目录下。

## 安卓版功能

- 行情列表 + 实时 WebSocket（币安 `fstream.binance.com`）
- 桌面悬浮窗（可拖动、缩小到单币、收缩为半隐胶囊吸附边缘）
- 搜索添加 / 热门币种 / 长按拖动排序
- 明暗主题、涨跌配色、悬浮窗透明度
- iOS 不支持系统级悬浮窗，故暂仅安卓

详见 `FloatBit_Android/README.md`。
