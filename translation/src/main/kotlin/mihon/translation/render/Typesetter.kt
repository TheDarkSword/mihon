package mihon.translation.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.os.LocaleList
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import logcat.LogPriority
import logcat.logcat
import mihon.translation.image.Strip
import mihon.translation.model.BlockKind
import mihon.translation.model.Box
import mihon.translation.model.TextBlock
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Draws Italian text over the original pages: the English text is covered with the sampled background colour
 * and the translation is fitted inside the bubble. Pages are processed in bands to bound memory.
 */
class Typesetter(private val context: Context, private val style: TextStyle = TextStyle()) {

    data class Placement(val block: TextBlock, val text: String)

    private val typeface = Typeface.createFromAsset(context.assets, "translation/" + TextStyle.fontFile(style.font))
    private val italian = Locale.ITALIAN

    /** Returns the encoded output images, one or more per source page. */
    fun render(strip: Strip, placements: List<Placement>, onImage: (name: String, bytes: ByteArray) -> Unit) {
        val visible = placements.filter { it.text.isNotBlank() }
        val start = System.currentTimeMillis()
        val patches = Eraser(context).use { eraser ->
            visible.associateWith { eraser.erase(strip, it.block.box) }.also {
                logcat(LogPriority.INFO, "MihonTranslation") {
                    "Erased ${it.size} regions in ${System.currentTimeMillis() - start} ms, " +
                        "${eraser.inpainted} inpainted in ${eraser.inpaintMillis} ms"
                }
            }
        }
        val sharedBubbles = visible.mapNotNull {
            it.block.bubble
        }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        // Bands are drawn in order (pages are cached one or two at a time) but encoded in parallel
        val encoder = Executors.newFixedThreadPool(ENCODERS)
        val encoded = mutableListOf<Future<Pair<String, ByteArray>>>()
        strip.slots.forEachIndexed { pageIndex, slot ->
            val onPage = visible.filter { p ->
                val area = p.block.bubble?.union(p.block.box) ?: p.block.box
                area.bottom > slot.top && area.top < slot.top + slot.stripHeight
            }
            var bandTop = 0
            var band = 0
            while (bandTop < slot.height) {
                val bandBottom = min(bandTop + BAND, slot.height)
                val bitmap = Bitmap.createBitmap(slot.width, bandBottom - bandTop, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawBitmap(
                    strip.page(pageIndex),
                    Rect(0, bandTop, slot.width, bandBottom),
                    Rect(0, 0, slot.width, bandBottom - bandTop),
                    null,
                )
                // Strip coordinates -> band pixels
                val toPixels = 1f / slot.scale
                canvas.translate(0f, -bandTop.toFloat())
                canvas.scale(toPixels, toPixels)
                canvas.translate(0f, -slot.top)
                onPage.forEach { paste(canvas, patches.getValue(it)) }
                onPage.forEach { draw(canvas, it, patches.getValue(it), it.block.bubble in sharedBubbles) }

                val name = "%03d_%02d.webp".format(pageIndex + 1, band + 1)
                encoded += encoder.submit<Pair<String, ByteArray>> {
                    val out = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, QUALITY, out)
                    bitmap.recycle()
                    name to out.toByteArray()
                }
                bandTop = bandBottom
                band++
            }
        }
        encoded.forEach { future -> future.get().let { (name, bytes) -> onImage(name, bytes) } }
        encoder.shutdown()
        patches.values.forEach { it.bitmap.recycle() }
    }

    private fun paste(canvas: Canvas, patch: Eraser.Patch) {
        canvas.save()
        canvas.clipRect(patch.erased.left, patch.erased.top, patch.erased.right, patch.erased.bottom)
        val area = patch.area
        canvas.drawBitmap(
            patch.bitmap,
            null,
            RectF(area.left, area.top, area.right, area.bottom),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        canvas.restore()
    }

    private fun draw(canvas: Canvas, placement: Placement, patch: Eraser.Patch, sharedBubble: Boolean) {
        val block = placement.block
        val text = if (style.uppercase) placement.text.uppercase(italian) else placement.text
        val bubble = block.bubble
        val target = when {
            // A bubble holding several lines gives each one only its own area
            block.kind == BlockKind.BUBBLE && bubble != null && !sharedBubble ->
                bubble.inset(bubble.width * BUBBLE_INSET, bubble.height * BUBBLE_INSET).union(block.box)
            else -> block.box.inset(-block.box.width * FREE_GROW, -block.box.height * FREE_GROW)
        }
        val dark = luminance(patch.background) < 0.5f
        val layout = fit(text, target, dark)
        val y = target.top + (target.height - layout.height) / 2

        canvas.save()
        canvas.translate(target.left, y)
        if (block.kind == BlockKind.FREE || !patch.flat) {
            // Outline so free text stays readable over artwork
            val stroke = TextPaint(layout.paint).apply {
                style = Paint.Style.STROKE
                strokeWidth = layout.paint.textSize * 0.22f
                strokeJoin = Paint.Join.ROUND
                color = if (dark) Color.BLACK else Color.WHITE
            }
            StaticLayout.Builder.obtain(text, 0, text.length, stroke, layout.width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NORMAL)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setLineSpacing(0f, LINE_SPACING)
                .setIncludePad(false)
                .build()
                .draw(canvas)
        }
        layout.draw(canvas)
        canvas.restore()
    }

    /**
     * Largest font size whose layout fits [target]. Hyphenation is used only when it allows a clearly larger text,
     * since Italian lettering rarely splits words.
     */
    private fun fit(text: String, target: Box, dark: Boolean): StaticLayout {
        val plain = fit(text, target, dark, Layout.HYPHENATION_FREQUENCY_NONE)
        val hyphenated = fit(text, target, dark, Layout.HYPHENATION_FREQUENCY_NORMAL)
        return if (plain.paint.textSize >= hyphenated.paint.textSize * HYPHENATION_GAIN) plain else hyphenated
    }

    private fun fit(text: String, target: Box, dark: Boolean, hyphenation: Int): StaticLayout {
        val width = ceil(target.width).toInt().coerceAtLeast(1)
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = this@Typesetter.typeface
            color = if (dark) Color.WHITE else Color.BLACK
            textLocales = LocaleList(italian)
            letterSpacing = 0.02f
            // Comic Neue is lighter than typical lettering
            style = Paint.Style.FILL_AND_STROKE
        }
        fun layout(size: Float): StaticLayout {
            paint.textSize = size
            paint.strokeWidth = size * WEIGHT * style.weight
            return StaticLayout.Builder.obtain(text, 0, text.length, TextPaint(paint), width)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .setHyphenationFrequency(hyphenation)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
                .setLineSpacing(0f, LINE_SPACING)
                .setIncludePad(false)
                .build()
        }
        fun fits(l: StaticLayout) = l.height <= target.height &&
            (0 until l.lineCount).all { l.getLineWidth(it) <= width + 0.5f } &&
            (0 until l.lineCount).none { line -> splitsWord(l, line, hyphenation) }

        var lo = MIN_SIZE
        var hi = max(MIN_SIZE, min(MAX_SIZE * style.scale, target.height * 0.5f * max(style.scale, 1f)))
        var best = layout(lo)
        repeat(12) {
            val mid = (lo + hi) / 2
            val candidate = layout(mid)
            if (fits(candidate)) {
                best = candidate
                lo = mid
            } else {
                hi = mid
            }
        }
        // A smaller text size shrinks the best fit, a larger one only lifts the cap above
        return layout(max(MIN_SIZE, best.paint.textSize * min(style.scale, 1f)))
    }

    /** Without hyphenation a word too long for the line is broken anyway: treat that as not fitting. */
    private fun splitsWord(layout: StaticLayout, line: Int, hyphenation: Int): Boolean {
        if (hyphenation != Layout.HYPHENATION_FREQUENCY_NONE || line == layout.lineCount - 1) return false
        val end = layout.getLineEnd(line)
        val text = layout.text
        return end < text.length && text[end - 1].isLetter() && text[end].isLetter()
    }

    private fun luminance(color: Int) =
        (0.299f * Color.red(color) + 0.587f * Color.green(color) + 0.114f * Color.blue(color)) / 255f

    private companion object {
        const val BAND = 2400
        const val QUALITY = 90
        const val ENCODERS = 3
        const val BUBBLE_INSET = 0.12f
        const val FREE_GROW = 0.1f
        const val MIN_SIZE = 10f
        const val MAX_SIZE = 44f
        const val WEIGHT = 0.045f
        const val HYPHENATION_GAIN = 0.85f
        const val LINE_SPACING = 0.95f
    }
}
