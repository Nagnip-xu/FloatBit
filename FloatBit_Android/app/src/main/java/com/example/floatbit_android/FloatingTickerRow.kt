package com.example.floatbit_android

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.widget.TextViewCompat

/** 紧凑行情行：数值列先按内容测量，再分配多余空间，避免价格被挤成省略号。 */
class FloatingTickerRow(context: Context, symbol: String) : LinearLayout(context) {
    val price = TextView(context).apply {
        maxLines = 1
        text = "…"
        setTextColor(Color.parseColor("#F2F4F8"))
        textSize = 12f
        gravity = Gravity.END
        fontFeatureSettings = "tnum"
        // WRAP_CONTENT 保留完整价格所需宽度；weight 只用于填充较短行的余量。
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 1f)
    }
    val change = TextView(context).apply {
        maxLines = 1
        setTextColor(Color.parseColor("#8B93A7"))
        textSize = 11f
        gravity = Gravity.END
        fontFeatureSettings = "tnum"
        minimumWidth = dp(54)
        layoutParams = LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(8)
        }
    }
    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(3)
        }
        addView(TextView(context).apply {
            text = ContractMarket.coin(symbol)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            contentDescription = text
            setTextColor(Color.parseColor("#F2F4F8"))
            textSize = 12f
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 9, 12, 1,
                TypedValue.COMPLEX_UNIT_SP)
            layoutParams = LayoutParams(dp(40), LayoutParams.WRAP_CONTENT)
        })
        addView(price)
        addView(change)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}
