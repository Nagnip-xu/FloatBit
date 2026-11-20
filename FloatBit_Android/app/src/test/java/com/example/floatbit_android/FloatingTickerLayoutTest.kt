package com.example.floatbit_android

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class, qualifiers = "w360dp-h800dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FloatingTickerLayoutTest {
    private fun context(fontScale: Float = 1f): Context {
        val base = RuntimeEnvironment.getApplication()
        return base.createConfigurationContext(Configuration(base.resources.configuration).apply {
            this.fontScale = fontScale
        })
    }
    private fun panel(context: Context, symbols: List<String>): Pair<View, List<FloatingTickerRow>> {
        val root = LayoutInflater.from(context).inflate(R.layout.float_window, null)
        val list = root.findViewById<LinearLayout>(R.id.float_list)
        val rows = symbols.map { FloatingTickerRow(context, it).also(list::addView) }
        return root to rows
    }
    private fun measure(root: View, availableDp: Int = 360) {
        val density = root.resources.displayMetrics.density
        root.measure(View.MeasureSpec.makeMeasureSpec((availableDp * density).toInt(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        root.layout(0, 0, root.measuredWidth, root.measuredHeight)
    }
    private fun assertComplete(view: TextView) {
        val text = view.text.toString()
        val layout = view.layout
        assertNotNull("Missing text layout for $text", layout)
        assertEquals("Text wrapped: $text", 1, layout.lineCount)
        assertEquals("Price was ellipsized: $text", 0, layout.getEllipsisCount(0))
        assertEquals("Text missing characters: $text", text.length, layout.getLineEnd(0))
        assertTrue("Text clipped: $text (${layout.getLineWidth(0)} > ${view.width})",
            layout.getLineWidth(0) <= view.width - view.totalPaddingLeft - view.totalPaddingRight + 1)
    }
    private fun screenshot(root: View, name: String) {
        val image = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(image))
        val target = File("build/reports/overlay/$name.png")
        target.parentFile.mkdirs()
        target.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
    }
    @Test fun muAndZecDisplayTheirFullPricesInTheCompactOverlay() {
        val (root, rows) = panel(context(), listOf("MUUSDT", "ZECUSDT"))
        listOf("608.12", "749.34").forEachIndexed { index, value ->
            rows[index].price.text = value
            rows[index].change.text = listOf("-13.42%", "-18.40%")[index]
        }
        measure(root)
        rows.forEach { assertComplete(it.price); assertComplete(it.change) }
        assertEquals(rows[0].price.right, rows[1].price.right)
        assertTrue("Overlay became unnecessarily wide", root.width / root.resources.displayMetrics.density <= 200)
        screenshot(root, "mu-zec")
    }
    @Test fun arrivingLongPricesExpandTheOverlayAndKeepAllRowsAligned() {
        val (root, rows) = panel(context(), listOf("MUUSDT", "BTCUSDT", "PEPEUSDT", "SKHYNIXUSDT"))
        measure(root)
        val initialWidth = root.width
        listOf("608.12", "103,456.78", "0.000001", "830.91").forEachIndexed { i, value ->
            rows[i].price.text = value
            rows[i].change.text = if (i % 2 == 0) "+123.45%" else "-18.40%"
        }
        measure(root, 320)
        assertTrue(root.width > initialWidth)
        rows.forEach {
            assertComplete(it.price); assertComplete(it.change)
            assertEquals(rows[0].price.right, it.price.right)
        }
        screenshot(root, "long-prices")
    }
    @Test fun largerSystemFontsStillShowCompletePriceAndChange() {
        val (root, rows) = panel(context(1.5f), listOf("BTCUSDT", "MUUSDT"))
        rows.forEach { it.price.text = "103,456.78"; it.change.text = "+123.45%" }
        measure(root, 320)
        rows.forEach { assertComplete(it.price); assertComplete(it.change) }
        screenshot(root, "large-font")
    }
}
