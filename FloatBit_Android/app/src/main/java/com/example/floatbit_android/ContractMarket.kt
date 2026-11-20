package com.example.floatbit_android

import java.util.Locale

/** 全部行情统一使用币安 USDⓈ-M 合约市场。 */
object ContractMarket {
    const val REST = "https://fapi.binance.com/fapi/v1"
    const val WS = "wss://fstream.binance.com/market/stream"

    /**
     * 币安 USDⓈ-M 合约的备用入口。
     *
     * 手机网络可能只解析/连通其中一个入口，因此所有 REST 请求都按顺序尝试这些地址。
     * 这些地址仍然全部指向币安合约 API，不包含现货接口。
     */
    val REST_HOSTS: List<String> = listOf(
        REST,
        "https://fapi1.binance.com/fapi/v1",
        "https://fapi2.binance.com/fapi/v1",
        "https://fapi3.binance.com/fapi/v1",
        "https://fapi4.binance.com/fapi/v1"
    )

    /** WebSocket 合约行情备用入口；路径必须保留 /market/stream。 */
    val WS_HOSTS: List<String> = listOf(
        WS,
        "wss://fstream1.binance.com/market/stream",
        "wss://fstream2.binance.com/market/stream",
        "wss://fstream3.binance.com/market/stream",
        "wss://fstream4.binance.com/market/stream"
    )

    fun restUrls(path: String): List<String> {
        val suffix = path.trimStart('/')
        return REST_HOSTS.map { "$it/$suffix" }
    }

    private val quotes = listOf("FDUSD", "USDT", "USDC", "USD1", "TUSD", "BUSD", "USD", "BTC", "ETH")

    fun normalize(keyword: String): String = keyword.trim().uppercase(Locale.ROOT)
        .replace(Regex("[\\s/_-]"), "")

    fun quote(symbol: String): String = quotes.firstOrNull {
        symbol.endsWith(it) && symbol.length > it.length
    } ?: ""

    fun coin(symbol: String): String = symbol.removeSuffix(quote(symbol))

    fun search(symbols: List<SymbolInfo>, keyword: String, limit: Int): List<SymbolInfo> {
        val kw = normalize(keyword)
        if (kw.isEmpty()) return emptyList()
        fun matchRank(s: SymbolInfo) = when {
            s.base == kw -> 0
            s.symbol == kw -> 1
            s.base.startsWith(kw) -> 2
            else -> 3
        }
        fun quoteRank(s: SymbolInfo) = when (s.quote) { "USDT" -> 0; "USDC" -> 1; else -> 2 }
        return symbols.filter { it.symbol.contains(kw) || it.base.contains(kw) }
            .sortedWith(compareBy(::matchRank, ::quoteRank, { it.symbol }))
            .take(limit)
    }
}

data class SymbolInfo(val symbol: String, val base: String, val quote: String)
