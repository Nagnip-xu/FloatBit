package com.example.floatbit_android

import java.util.Locale

/** 全部行情统一使用币安 USDⓈ-M 合约市场。 */
object ContractMarket {
    const val REST = "https://fapi.binance.com/fapi/v1"
    const val WS = "wss://fstream.binance.com/market/stream"

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
