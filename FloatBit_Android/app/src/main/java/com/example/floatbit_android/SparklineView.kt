package com.example.floatbit_android

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View

/** 迷你走势图 (sparkline): 用收盘价序列画带渐变填充的折线 */
class SparklineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var data: List<Double> = emptyList()
    private var lineColor: Int = Color.parseColor("#18C08A")

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setData(closes: List<Double>, color: Int) {
        data = closes
        lineColor = color
        invalidate()
    }

    /** 仅更新颜色 (数据不变) */
    fun setColor(color: Int) {
        if (lineColor != color) {
            lineColor = color
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (data.size < 2) return
        val w = width.toFloat()
        val h = height.toFloat()
        val pad = 4f
        val min = data.min()
        val max = data.max()
        val range = (max - min).takeIf { it != 0.0 } ?: 1.0
        val n = data.size
        val innerW = w - pad * 2
        val innerH = h - pad * 2

        val line = Path()
        val fill = Path()
        for (i in data.indices) {
            val x = pad + (i.toFloat() / (n - 1)) * innerW
            val y = pad + (1 - ((data[i] - min) / range).toFloat()) * innerH
            if (i == 0) {
                line.moveTo(x, y)
                fill.moveTo(x, h)
                fill.lineTo(x, y)
            } else {
                line.lineTo(x, y)
                fill.lineTo(x, y)
            }
        }
        fill.lineTo(pad + innerW, h)
        fill.close()

        // 渐变填充 (顶部半透明 -> 底部透明)
        val a = (0x40 shl 24) or (lineColor and 0x00FFFFFF)
        fillPaint.shader = android.graphics.LinearGradient(
            0f, 0f, 0f, h,
            a, (lineColor and 0x00FFFFFF), android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawPath(fill, fillPaint)

        linePaint.color = lineColor
        canvas.drawPath(line, linePaint)
    }
}
