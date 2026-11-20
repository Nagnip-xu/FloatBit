package com.example.floatbit_android

/** 单个交易对的实时行情 */
data class Ticker(
    val symbol: String,      // 如 BTCUSDT
    val last: Double,        // 最新价
    val changePct: Double,   // 24h 涨跌幅 (百分比, 如 1.5 表示 +1.5%)
    val error: String? = null
) {
    /** 基础币名, 如 BTC */
    val coin: String
        get() = ContractMarket.coin(symbol)

    /** 计价币, 如 USDT */
    val quote: String
        get() = ContractMarket.quote(symbol)

    /** 格式化后的价格字符串 */
    fun formatPrice(): String = when {
        last >= 1000 -> "%,.2f".format(last)
        last >= 1 -> "%.2f".format(last)
        last >= 0.01 -> "%.4f".format(last)
        else -> "%.6f".format(last)
    }

    /** 格式化后的涨跌幅, 如 +1.50% */
    fun formatChange(): String {
        val sign = if (changePct >= 0) "+" else ""
        return "$sign%.2f%%".format(changePct)
    }
}
