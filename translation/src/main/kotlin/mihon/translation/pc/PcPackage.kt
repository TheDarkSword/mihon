package mihon.translation.pc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import mihon.translation.ChapterTranslator
import mihon.translation.image.Strip
import mihon.translation.model.BlockKind
import mihon.translation.model.Box
import mihon.translation.model.TextBlock
import java.io.ByteArrayOutputStream
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * What the PC model looks at: the strip cut around the text regions with enough margin to see who speaks, every
 * region outlined and labeled with its id, cuts stacked into a few tall images.
 */
object PcPackage {
    private const val SCALE = 0.55f
    private const val PAD = 450f
    private const val MAX_CUT = 2000f
    private const val STACK_HEIGHT = 1560
    private const val SEPARATOR = 8
    private const val WEBP_QUALITY = 82
    private val MARK = Color.rgb(230, 0, 140)

    /** The lines the model translates: credits, links and unreadable scraps stay out, as with Gemini. */
    fun lines(blocks: List<TextBlock>, series: String): List<PcLineSpec> = blocks
        .filter { it.vision || !ChapterTranslator.isSkippable(it, series) }
        .map {
            PcLineSpec(
                id = it.id,
                region = if (it.kind == BlockKind.BUBBLE) "bubble" else "free",
                text = it.text,
                max = maxOf(ChapterTranslator.maxLength(it.text), (it.box.area / CHAR_AREA).roundToInt()),
            )
        }

    /** Writes the images through [write] and returns them in reading order with the regions each one shows. */
    fun images(
        strip: Strip,
        blocks: List<TextBlock>,
        write: (name: String, bytes: ByteArray) -> Unit,
    ): List<PcImageSpec> {
        val cuts = windows(blocks, strip.height).map { (top, bottom) ->
            val ids = blocks.filter { it.box.bottom >= top && it.box.top <= bottom }.map { it.id }
            Cut(top, bottom, ids)
        }
        val groups = mutableListOf<MutableList<Cut>>()
        for (cut in cuts) {
            val group = groups.lastOrNull()
            if (group != null && group.sumOf { it.scaledHeight + SEPARATOR } + cut.scaledHeight <= STACK_HEIGHT) {
                group += cut
            } else {
                groups += mutableListOf(cut)
            }
        }
        return groups.mapIndexed { index, group ->
            val name = String.format(Locale.ROOT, "images/%02d.webp", index)
            write(name, encode(stack(strip, blocks, group)))
            PcImageSpec(name, group.flatMap { it.ids }.distinct())
        }
    }

    private class Cut(val top: Float, val bottom: Float, val ids: List<Int>) {
        val scaledHeight get() = ceil((bottom - top) * SCALE).toInt()
    }

    private fun windows(blocks: List<TextBlock>, height: Float): List<Pair<Float, Float>> {
        val spans = blocks.map { maxOf(0f, it.box.top - PAD) to minOf(height, it.box.bottom + PAD) }
            .sortedBy { it.first }
        val merged = mutableListOf<FloatArray>()
        for ((top, bottom) in spans) {
            val last = merged.lastOrNull()
            if (last != null && top <= last[1] + PAD / 2) {
                last[1] = maxOf(last[1], bottom)
            } else {
                merged += floatArrayOf(top, bottom)
            }
        }
        return merged.flatMap { (top, bottom) ->
            val parts = ceil((bottom - top) / MAX_CUT).toInt().coerceAtLeast(1)
            val step = (bottom - top) / parts
            (0 until parts).map { top + it * step to top + (it + 1) * step }
        }
    }

    private fun stack(strip: Strip, blocks: List<TextBlock>, group: List<Cut>): Bitmap {
        val width = ceil(Strip.WIDTH * SCALE).toInt()
        val height = group.sumOf { it.scaledHeight } + SEPARATOR * (group.size - 1)
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.GRAY)
        var y = 0f
        for (cut in group) {
            val image = strip.crop(Box(0f, cut.top, Strip.WIDTH.toFloat(), cut.bottom), SCALE)
            mark(image, blocks, cut)
            canvas.drawBitmap(image, 0f, y, null)
            y += cut.scaledHeight + SEPARATOR
            image.recycle()
        }
        return out
    }

    private fun mark(image: Bitmap, blocks: List<TextBlock>, cut: Cut) {
        val canvas = Canvas(image)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = MARK
            strokeWidth = 3 * SCALE
        }
        val fill = Paint().apply { color = MARK }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26 * SCALE
            isFakeBoldText = true
        }
        val labelHeight = 32 * SCALE
        blocks.filter { it.id in cut.ids }.forEach { b ->
            val rect = RectF(
                (b.box.left - 4) * SCALE,
                (b.box.top - cut.top - 4) * SCALE,
                (b.box.right + 4) * SCALE,
                (b.box.bottom - cut.top + 4) * SCALE,
            )
            canvas.drawRect(rect, stroke)
            val label = b.id.toString()
            val top = if (rect.top >= labelHeight) rect.top - labelHeight else rect.bottom
            canvas.drawRect(rect.left, top, rect.left + text.measureText(label) + 10 * SCALE, top + labelHeight, fill)
            canvas.drawText(label, rect.left + 5 * SCALE, top + labelHeight * 0.78f, text)
        }
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        return ByteArrayOutputStream().use {
            bitmap.compress(format, WEBP_QUALITY, it)
            bitmap.recycle()
            it.toByteArray()
        }
    }

    /** Strip pixels per lettered character, to size lines the OCR could not read. */
    private const val CHAR_AREA = 900f
}
