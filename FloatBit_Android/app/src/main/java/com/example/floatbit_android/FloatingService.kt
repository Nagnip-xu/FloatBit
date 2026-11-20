package com.example.floatbit_android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 悬浮窗前台服务: 把一个小窗口贴到所有应用之上, 实时显示行情, 可拖动。
 */
class FloatingService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var rootView: View
    private lateinit var listContainer: LinearLayout
    private lateinit var panelExpanded: View
    private lateinit var panelCollapsed: View
    private lateinit var params: WindowManager.LayoutParams

    private var client: MarketClient? = null
    private var redUp = false
    private var collapsed = false
    private var minimized = false
    private var edgeAnimator: android.animation.ValueAnimator? = null
    private lateinit var btnMini: TextView
    private val rows = HashMap<String, RowViews>()
    private val rowViews = ArrayList<View>() // 各行容器, 用于"缩小"时只显示第一行

    private val green = Color.parseColor("#18C08A")
    private val red = Color.parseColor("#F2566A")
    private val textMain = Color.parseColor("#F2F4F8")
    private val textDim = Color.parseColor("#8B93A7")

    private data class RowViews(val price: TextView, val change: TextView)

    companion object {
        private const val CHANNEL_ID = "floatbit_overlay"
        private const val NOTI_ID = 1
        const val EXTRA_REFRESH = "refresh"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        MarketNetwork.configure(Prefs.getProxy(this))
        MainActivity.floatingRunningFlag = true
        startAsForeground()
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        redUp = Prefs.isRedUp(this)
        buildView()
        startMarket()
    }

    /** 自选/配色变化时, MainActivity 会再次 start 本服务并带上标记, 这里热更新 */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra(EXTRA_REFRESH, false) == true && ::rootView.isInitialized) {
            refreshContent()
        }
        return START_STICKY
    }

    /** 重新读取自选与配色, 重建行并重订阅 */
    private fun refreshContent() {
        redUp = Prefs.isRedUp(this)
        // 重建行
        listContainer.removeAllViews()
        rows.clear()
        rowViews.clear()
        val symbols = Prefs.getSymbols(this)
        symbols.forEach { addRow(it) }
        // 维持当前缩小状态
        setMinimized(minimized)
        // 应用最新透明度
        rootView.alpha = Prefs.getOpacity(this).coerceIn(40, 100) / 100f
        // 重订阅
        client?.updateSymbols(symbols)
    }

    private fun startAsForeground() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val mgr = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(
                CHANNEL_ID, getString(R.string.float_channel),
                NotificationManager.IMPORTANCE_MIN
            )
            mgr.createNotificationChannel(ch)
        }
        val noti: Notification = Notification.Builder(
            this,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) CHANNEL_ID else ""
        )
            .setContentTitle("FloatBit")
            .setContentText("实时行情悬浮窗运行中")
            .setSmallIcon(R.mipmap.ic_launcher)
            .build()
        startForeground(NOTI_ID, noti)
    }

    private fun buildView() {
        rootView = LayoutInflater.from(this).inflate(R.layout.float_window, null)
        listContainer = rootView.findViewById(R.id.float_list)
        panelExpanded = rootView.findViewById(R.id.panel_expanded)
        panelCollapsed = rootView.findViewById(R.id.panel_collapsed)

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 40
        params.y = 120

        // "✕" -> 收缩成小悬浮窗 (胶囊)
        rootView.findViewById<TextView>(R.id.float_close).setOnClickListener { setCollapsed(true) }
        // "—" / "+" -> 缩小/还原 (只显示一个币种)
        btnMini = rootView.findViewById(R.id.float_mini)
        btnMini.setOnClickListener { setMinimized(!minimized) }
        // 展开 (点小胶囊)
        panelCollapsed.setOnClickListener { setCollapsed(false) }

        // 两个面板都支持拖动
        enableDrag(panelExpanded)
        enableDrag(panelCollapsed)

        // 应用不透明度
        rootView.alpha = Prefs.getOpacity(this).coerceIn(40, 100) / 100f

        wm.addView(rootView, params)

        // 初始化行
        val symbols = Prefs.getSymbols(this)
        symbols.forEach { addRow(it) }
    }

    /** 切换收缩/展开 (小胶囊图标) */
    private fun setCollapsed(value: Boolean) {
        collapsed = value
        panelExpanded.visibility = if (value) View.GONE else View.VISIBLE
        panelCollapsed.visibility = if (value) View.VISIBLE else View.GONE
        if (value) {
            // 收缩态: 半透明 + 吸附到最近一侧边缘
            panelCollapsed.alpha = 0.55f
            panelCollapsed.post { snapCollapsedToEdge() }
        } else {
            panelCollapsed.alpha = 1f
            // 展开时若窗口贴边/超出, 拉回屏幕内
            panelExpanded.post {
                val screenW = resources.displayMetrics.widthPixels
                val w = if (panelExpanded.width > 0) panelExpanded.width else dp(200)
                if (params.x < 0) params.x = dp(8)
                else if (params.x + w > screenW) params.x = screenW - w - dp(8)
                if (params.y < 0) params.y = dp(8)
                try { wm.updateViewLayout(rootView, params) } catch (_: Exception) {}
            }
        }
    }

    /** 把收缩胶囊丝滑吸附到最近边缘, 一半藏到屏幕外 */
    private fun snapCollapsedToEdge() {
        if (!collapsed) return
        val screenW = resources.displayMetrics.widthPixels
        val pillW = if (panelCollapsed.width > 0) panelCollapsed.width else dp(52)
        val center = params.x + pillW / 2
        // 藏到屏幕外约 45%, 露出带 ◈ 的另一半
        val hidden = (pillW * 0.45f).toInt()
        val targetX = if (center < screenW / 2) -hidden else screenW - pillW + hidden
        animateX(targetX)
    }

    /** 用动画平滑移动悬浮窗的 x 坐标 */
    private fun animateX(targetX: Int) {
        edgeAnimator?.cancel()
        val startX = params.x
        if (startX == targetX) return
        edgeAnimator = android.animation.ValueAnimator.ofInt(startX, targetX).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener { a ->
                params.x = a.animatedValue as Int
                try { wm.updateViewLayout(rootView, params) } catch (_: Exception) {}
            }
            start()
        }
    }

    /** 缩小: 只显示第一个币 (隐藏其余行) */
    private fun setMinimized(value: Boolean) {
        minimized = value
        rowViews.forEachIndexed { i, v ->
            v.visibility = if (value && i > 0) View.GONE else View.VISIBLE
        }
        // 缩小时按钮显示 "+"(还原), 否则显示 "—"(缩小)
        if (::btnMini.isInitialized) btnMini.text = if (value) "+" else "—"
    }

    private fun addRow(symbol: String) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = dp(3)
            layoutParams = lp
        }
        val coin = TextView(this).apply {
            text = ContractMarket.coin(symbol)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            contentDescription = text
            androidx.core.widget.TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 9, 12, 1,
                android.util.TypedValue.COMPLEX_UNIT_SP)
            setTextColor(textMain)
            textSize = 12f
            val lp = LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT)
            layoutParams = lp
        }
        val price = TextView(this).apply {
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = "…"
            setTextColor(textMain)
            textSize = 12f
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            layoutParams = lp
            gravity = Gravity.END
        }
        val change = TextView(this).apply {
            maxLines = 1
            text = ""
            setTextColor(textDim)
            textSize = 11f
            val lp = LinearLayout.LayoutParams(dp(54), LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.marginStart = dp(8)
            layoutParams = lp
            gravity = Gravity.END
        }
        row.addView(coin)
        row.addView(price)
        row.addView(change)
        listContainer.addView(row)
        rows[symbol] = RowViews(price, change)
        rowViews.add(row)
    }

    private fun startMarket() {
        val symbols = Prefs.getSymbols(this)
        client = MarketClient(symbols) { t -> updateRow(t) }
        client?.start()
    }

    private fun updateRow(t: Ticker) {
        val rv = rows[t.symbol] ?: return
        rv.price.text = if (t.last.isNaN()) t.error ?: "…" else t.formatPrice()
        rv.change.text = if (t.last.isNaN()) "" else t.formatChange()
        val up = t.changePct >= 0
        val color = if (up) (if (redUp) red else green) else (if (redUp) green else red)
        rv.change.setTextColor(color)
    }

    private fun enableDrag(view: View) {
        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        var moved = false
        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = params.x
                    initY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (kotlin.math.abs(dx) > 8 || kotlin.math.abs(dy) > 8) moved = true
                    params.x = initX + dx
                    params.y = initY + dy
                    wm.updateViewLayout(rootView, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    // 未拖动 -> 视为点击, 触发 onClick (用于展开小胶囊)
                    if (!moved) {
                        v.performClick()
                    } else if (collapsed) {
                        // 收缩态拖动结束 -> 重新吸附到最近边缘
                        snapCollapsedToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        super.onDestroy()
        MainActivity.floatingRunningFlag = false
        edgeAnimator?.cancel()
        client?.stop()
        if (::rootView.isInitialized) {
            try { wm.removeView(rootView) } catch (_: Exception) {}
        }
    }
}
