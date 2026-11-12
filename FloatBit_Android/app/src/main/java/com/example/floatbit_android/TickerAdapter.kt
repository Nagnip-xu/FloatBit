package com.example.floatbit_android

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * 行情列表适配器 (含走势图)。
 * 价格更新只刷新数字, 不重建整行 (用 payload 局部更新)。
 */
class TickerAdapter(
    private var redUp: Boolean,
    private val onRemove: (String) -> Unit
) : RecyclerView.Adapter<TickerAdapter.VH>() {

    private val items = mutableListOf<Ticker>()

    private val green = Color.parseColor("#18C08A")
    private val red = Color.parseColor("#F2566A")

    companion object {
        private const val PAYLOAD_PRICE = "price"
    }

    fun setColorScheme(redUp: Boolean) {
        this.redUp = redUp
        notifyDataSetChanged()
    }

    /** 用最新行情更新某个币种: 仅局部刷新价格/涨跌 */
    fun updateTicker(t: Ticker) {
        val idx = items.indexOfFirst { it.symbol == t.symbol }
        if (idx >= 0) {
            items[idx] = t
            notifyItemChanged(idx, PAYLOAD_PRICE)
        }
    }

    fun setSymbols(symbols: List<String>) {
        items.clear()
        symbols.forEach { items.add(Ticker(it, Double.NaN, Double.NaN)) }
        notifyDataSetChanged()
    }

    /** 移动顺序 (拖拽排序用) */
    fun moveItem(from: Int, to: Int) {
        if (from < 0 || to < 0 || from >= items.size || to >= items.size) return
        val item = items.removeAt(from)
        items.add(to, item)
        notifyItemMoved(from, to)
    }

    fun currentSymbols(): List<String> = items.map { it.symbol }

    private fun colorFor(up: Boolean): Int =
        if (up) (if (redUp) red else green) else (if (redUp) green else red)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.item_ticker, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    /** 局部更新: 只改价格和涨跌, 不动走势图/币名 */
    override fun onBindViewHolder(holder: VH, position: Int, payloads: MutableList<Any>) {
        if (payloads.contains(PAYLOAD_PRICE)) {
            val t = items[position]
            bindPrice(holder, t)
            // 同步走势图颜色 (不重新请求数据, 只改颜色)
            if (!t.changePct.isNaN()) {
                holder.spark.setColor(colorFor(t.changePct >= 0))
            }
        } else {
            onBindViewHolder(holder, position)
        }
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val t = items[position]
        holder.coin.text = t.coin
        holder.pair.text = t.quote
        bindPrice(holder, t)
        // 走势图: 按币种加载 (与实时价格无关), 颜色用当前涨跌方向
        loadSpark(holder, t)
        holder.remove.setOnClickListener { onRemove(t.symbol) }
    }

    private fun loadSpark(holder: VH, t: Ticker) {
        val symbol = t.symbol
        // 先按当前涨跌给个颜色 (NaN 时默认涨色)
        val up = if (t.changePct.isNaN()) true else t.changePct >= 0
        KlineRepo.getCloses(symbol) { closes ->
            // 确认此 holder 仍对应该币种再绘制 (避免错位)
            val pos = holder.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION && items[pos].symbol == symbol) {
                val curUp = if (items[pos].changePct.isNaN()) true else items[pos].changePct >= 0
                holder.spark.setData(closes, colorFor(curUp))
            }
        }
    }

    private fun bindPrice(holder: VH, t: Ticker) {
        if (t.last.isNaN()) {
            holder.price.text = "…"
            holder.change.text = ""
        } else {
            holder.price.text = t.formatPrice()
            holder.change.text = t.formatChange()
            holder.change.setTextColor(colorFor(t.changePct >= 0))
        }
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val coin: TextView = v.findViewById(R.id.tv_coin)
        val pair: TextView = v.findViewById(R.id.tv_pair)
        val price: TextView = v.findViewById(R.id.tv_price)
        val change: TextView = v.findViewById(R.id.tv_change)
        val remove: TextView = v.findViewById(R.id.btn_remove)
        val spark: SparklineView = v.findViewById(R.id.spark)
    }
}
