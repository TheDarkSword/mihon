package mihon.translation.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import mihon.translation.model.Box
import java.io.Closeable
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The pages of a chapter stacked vertically and scaled to a common [WIDTH], without ever decoding the whole
 * strip: regions are decoded from the pages on demand.
 */
class Strip(private val pages: List<PageImage>) : Closeable {

    class PageImage(val name: String, private val open: () -> BitmapRegionDecoder) {
        private var decoder: BitmapRegionDecoder? = null

        fun decoder(): BitmapRegionDecoder = decoder ?: open().also { decoder = it }

        fun release() {
            decoder?.recycle()
            decoder = null
        }
    }

    class Slot(val image: PageImage, val width: Int, val height: Int, val top: Float, val scale: Float) {
        val stripHeight get() = height * scale
    }

    val slots: List<Slot>
    val height: Float

    init {
        var y = 0f
        slots = pages.map { page ->
            val decoder = page.decoder()
            val scale = WIDTH.toFloat() / decoder.width
            Slot(page, decoder.width, decoder.height, y, scale).also { y += it.stripHeight }
        }
        height = y
    }

    fun pageAt(y: Float): Int = slots.indexOfLast { it.top <= y }.coerceAtLeast(0)

    // Region decoding re-reads the page from its start every time, so the last pages are kept decoded
    private val decoded = object : LinkedHashMap<Int, Bitmap>(CACHED_PAGES + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, Bitmap>) = size > CACHED_PAGES
    }

    /** The whole page at its own resolution. Shared: callers must not modify or recycle it. */
    @Synchronized
    fun page(index: Int): Bitmap = decoded.getOrPut(index) {
        val slot = slots[index]
        slot.image.decoder().decodeRegion(
            Rect(0, 0, slot.width, slot.height),
            BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 },
        )
    }

    /** Renders the strip region [box] into a bitmap scaled by [scale] relative to strip coordinates. */
    fun crop(box: Box, scale: Float, config: Bitmap.Config = Bitmap.Config.ARGB_8888): Bitmap {
        val outW = ceil(box.width * scale).toInt().coerceAtLeast(1)
        val outH = ceil(box.height * scale).toInt().coerceAtLeast(1)
        val out = Bitmap.createBitmap(outW, outH, config)
        val canvas = Canvas(out)
        canvas.drawColor(android.graphics.Color.WHITE)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        for ((index, slot) in slots.withIndex()) {
            val top = maxOf(box.top, slot.top)
            val bottom = minOf(box.bottom, slot.top + slot.stripHeight)
            if (bottom <= top) continue
            val src = Rect(
                floor(box.left / slot.scale).toInt().coerceIn(0, slot.width - 1),
                floor((top - slot.top) / slot.scale).toInt().coerceIn(0, slot.height - 1),
                ceil(box.right / slot.scale).toInt().coerceIn(1, slot.width),
                ceil((bottom - slot.top) / slot.scale).toInt().coerceIn(1, slot.height),
            )
            if (src.width() <= 0 || src.height() <= 0) continue
            val dst = RectF(
                (src.left * slot.scale - box.left) * scale,
                (src.top * slot.scale + slot.top - box.top) * scale,
                (src.right * slot.scale - box.left) * scale,
                (src.bottom * slot.scale + slot.top - box.top) * scale,
            )
            canvas.drawBitmap(page(index), src, dst, paint)
        }
        return out
    }

    override fun close() {
        synchronized(this) { decoded.clear() }
        pages.forEach { it.release() }
    }

    companion object {
        const val WIDTH = 800
        private const val CACHED_PAGES = 2
    }
}
