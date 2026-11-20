package com.example.floatbit_android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: TickerAdapter
    private lateinit var client: MarketClient
    private var redUp = false
    private var dark = true

    private lateinit var btnColor: TextView
    private lateinit var btnFloat: TextView
    private lateinit var btnTheme: TextView
    private lateinit var searchResults: LinearLayout
    private lateinit var input: EditText
    private val refreshHandler = Handler(Looper.getMainLooper())
    private val refreshTask = object : Runnable {
        override fun run() {
            refreshSymbols()
            refreshHandler.postDelayed(this, 60_000)
        }
    }

    private val hotCoins = listOf(
        "BTC", "ETH", "BNB", "SOL", "XRP", "DOGE", "ADA",
        "TRX", "AVAX", "LINK", "DOT", "SHIB", "PEPE", "LTC"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = Prefs.isDark(this)
        AppCompatDelegate.setDefaultNightMode(
            if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        MarketNetwork.configure(Prefs.getProxy(this))

        redUp = Prefs.isRedUp(this)
        val symbols = Prefs.getSymbols(this).toMutableList()

        val list = findViewById<RecyclerView>(R.id.list)
        list.layoutManager = LinearLayoutManager(this)
        adapter = TickerAdapter(redUp) { symbol -> removeSymbol(symbol) }
        list.adapter = adapter
        adapter.setSymbols(symbols)
        setupDragSort(list)

        client = MarketClient(symbols) { ticker -> adapter.updateTicker(ticker) }
        client.start()

        setupHeader()
        setupHotCoins()
    }

    // ---------- 拖拽排序 ----------
    private fun setupDragSort(list: RecyclerView) {
        val cb = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                rv: RecyclerView,
                vh: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                adapter.moveItem(vh.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

            override fun isLongPressDragEnabled() = true

            // 拖动结束后保存新顺序并同步
            override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
                super.clearView(rv, vh)
                val symbols = adapter.currentSymbols()
                Prefs.setSymbols(this@MainActivity, symbols)
                client.updateSymbols(symbols)
                notifyFloating()
            }
        }
        ItemTouchHelper(cb).attachToRecyclerView(list)
    }

    // ---------- 头部控件 ----------
    private fun setupHeader() {
        input = findViewById(R.id.input_symbol)
        searchResults = findViewById(R.id.search_results)

        // 输入即搜索
        input.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {
                renderSearch(s?.toString() ?: "")
                refreshSymbols()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
        // 搜索按钮 / 回车: 收起键盘 (结果已实时显示)
        findViewById<TextView>(R.id.btn_add).setOnClickListener {
            refreshSymbols(force = true)
        }
        findViewById<TextView>(R.id.refresh_symbols).setOnClickListener {
            client.start()
            notifyFloating()
            refreshSymbols(force = true)
        }
        findViewById<TextView>(R.id.network_settings).setOnClickListener {
            NetworkSettingsDialog.show(this) {
                MarketNetwork.configure(Prefs.getProxy(this))
                SymbolRepo.resetNetwork()
                KlineRepo.resetNetwork()
                adapter.setSymbols(adapter.currentSymbols())
                client.start()
                notifyFloating()
                refreshSymbols(force = true)
            }
        }
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                renderSearch(input.text.toString()); true
            } else false
        }

        btnColor = findViewById(R.id.btn_color)
        updateColorButton()
        btnColor.setOnClickListener {
            redUp = !redUp
            Prefs.setRedUp(this, redUp)
            adapter.setColorScheme(redUp)
            updateColorButton()
            notifyFloating()
        }

        btnFloat = findViewById(R.id.btn_float)
        btnFloat.setOnClickListener { toggleFloating() }

        btnTheme = findViewById(R.id.btn_theme)
        btnTheme.text = if (dark) "🌙" else "☀"
        btnTheme.setOnClickListener {
            dark = !dark
            Prefs.setDark(this, dark)
            AppCompatDelegate.setDefaultNightMode(
                if (dark) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO
            )
            recreate()
        }

        val seek = findViewById<SeekBar>(R.id.seek_opacity)
        val tvOpacity = findViewById<TextView>(R.id.tv_opacity)
        val op = Prefs.getOpacity(this)
        seek.progress = op
        tvOpacity.text = "$op%"
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                tvOpacity.text = "${progress.coerceIn(40, 100)}%"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) {
                val v = (sb?.progress ?: 95).coerceIn(40, 100)
                Prefs.setOpacity(this@MainActivity, v)
                notifyFloating()
            }
        })
    }

    // ---------- 搜索结果 ----------
    private fun refreshSymbols(force: Boolean = false) {
        SymbolRepo.load(force) {
            if (!isDestroyed) {
                findViewById<TextView>(R.id.symbol_status).text = SymbolRepo.error ?: "币安 USDⓈ-M 合约"
                renderSearch(input.text.toString())
            }
        }
        if (SymbolRepo.loading) findViewById<TextView>(R.id.symbol_status).text = "正在更新合约列表…"
    }

    private fun renderSearch(keyword: String) {
        val kw = keyword.trim()
        searchResults.removeAllViews()
        if (kw.isEmpty()) {
            searchResults.visibility = android.view.View.GONE
            return
        }
        val results = SymbolRepo.search(kw, limit = 8)
        if (results.isEmpty()) {
            val hint = TextView(this).apply {
                text = if (SymbolRepo.loading) "正在更新合约列表…" else SymbolRepo.error
                    ?: "没有找到可交易的合约，请尝试完整代码（如 MU、SNDK、SKHYNIX）"
                textSize = 12f
                setTextColor(getColorCompat(R.color.text_dim))
                val pad = (12 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            searchResults.addView(hint)
            searchResults.visibility = android.view.View.VISIBLE
            return
        }
        val owned = adapter.currentSymbols()
        results.forEach { info ->
            val row = LayoutInflater.from(this)
                .inflate(R.layout.item_search, searchResults, false)
            row.findViewById<TextView>(R.id.s_base).text = info.base
            row.findViewById<TextView>(R.id.s_quote).text = "${info.quote} · 合约"
            val addLabel = row.findViewById<TextView>(R.id.s_add)
            val already = owned.contains(info.symbol)
            addLabel.text = if (already) "已添加" else "+ 添加"
            addLabel.setTextColor(
                getColorCompat(if (already) R.color.text_dim else R.color.accent)
            )
            if (!already) {
                row.setOnClickListener {
                    addSymbol(info.symbol)
                    input.text.clear()
                    searchResults.visibility = android.view.View.GONE
                    hideKeyboard()
                }
            }
            searchResults.addView(row)
        }
        searchResults.visibility = android.view.View.VISIBLE
    }

    private fun hideKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.hideSoftInputFromWindow(input.windowToken, 0)
    }

    // ---------- 热门币种 ----------
    private fun setupHotCoins() {
        val row = findViewById<LinearLayout>(R.id.hot_row)
        val density = resources.displayMetrics.density
        hotCoins.forEach { coin ->
            val chip = TextView(this).apply {
                text = coin
                textSize = 13f
                setTextColor(getColorCompat(R.color.text_main))
                setBackgroundResource(R.drawable.bg_chip)
                gravity = android.view.Gravity.CENTER
                val padH = (16 * density).toInt()
                val padV = (8 * density).toInt()
                setPadding(padH, padV, padH, padV)
                val lp = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                lp.marginEnd = (8 * density).toInt()
                layoutParams = lp
                setOnClickListener { addSymbol("${coin}USDT") }
            }
            row.addView(chip)
        }
    }

    private fun getColorCompat(id: Int): Int =
        androidx.core.content.ContextCompat.getColor(this, id)

    // ---------- 增删 ----------
    private fun addSymbol(raw: String) {
        val s = ContractMarket.normalize(raw)
        if (s.isEmpty()) return
        val current = adapter.currentSymbols().toMutableList()
        if (current.contains(s)) {
            Toast.makeText(this, "$s 已在列表中", Toast.LENGTH_SHORT).show()
            return
        }
        current.add(s)
        applySymbols(current)
        Toast.makeText(this, "已添加 $s", Toast.LENGTH_SHORT).show()
    }

    private fun removeSymbol(symbol: String) {
        val current = adapter.currentSymbols().toMutableList()
        current.remove(symbol)
        applySymbols(current)
    }

    private fun applySymbols(symbols: List<String>) {
        Prefs.setSymbols(this, symbols)
        adapter.setSymbols(symbols)
        client.updateSymbols(symbols)
        notifyFloating()
    }

    private fun updateColorButton() {
        btnColor.text = if (redUp) "🔴 涨 / 🟢 跌" else "🟢 涨 / 🔴 跌"
    }

    // ---------- 悬浮窗 ----------
    private fun toggleFloating() {
        if (floatingRunningFlag) {
            stopService(Intent(this, FloatingService::class.java))
            btnFloat.text = getString(R.string.start_float)
            return
        }
        if (!canDrawOverlays()) {
            Toast.makeText(this, getString(R.string.need_overlay), Toast.LENGTH_LONG).show()
            requestOverlay()
            return
        }
        startFloating()
        btnFloat.text = getString(R.string.stop_float)
    }

    private fun startFloating(refresh: Boolean = false) {
        val intent = Intent(this, FloatingService::class.java)
        if (refresh) intent.putExtra(FloatingService.EXTRA_REFRESH, true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    /** 自选/配色/透明度变化时, 若悬浮窗开着则热更新 */
    private fun notifyFloating() {
        if (floatingRunningFlag) startFloating(refresh = true)
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        }
    }

    override fun onResume() {
        super.onResume()
        if (::btnFloat.isInitialized) {
            btnFloat.text = if (floatingRunningFlag) getString(R.string.stop_float)
            else getString(R.string.start_float)
        }
        if (::client.isInitialized) client.start()
        refreshHandler.post(refreshTask)
    }

    override fun onPause() {
        refreshHandler.removeCallbacks(refreshTask)
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        client.stop()
    }

    companion object {
        var floatingRunningFlag = false
    }
}
