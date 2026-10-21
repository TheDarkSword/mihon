package mihon.translation.render

import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import mihon.translation.image.Strip
import mihon.translation.model.Box
import java.io.Closeable
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil

/**
 * Removes the original lettering. Only the pixels of the letters are touched: on flat backgrounds they take the
 * background colour, on artwork they are rebuilt by MI-GAN inpainting.
 */
class Eraser(context: Context) : Closeable {

    /** A cleaned piece of the strip, at page resolution, to paint back before typesetting. */
    class Patch(val area: Box, val erased: Box, val bitmap: Bitmap, val background: Int, val flat: Boolean)

    private val env = OrtEnvironment.getEnvironment()
    private val lazySession = lazy {
        val model = context.assets.open("translation/migan.onnx").use { it.readBytes() }
        env.createSession(model, OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) })
    }
    private val session: OrtSession by lazySession

    var inpainted = 0
        private set
    var inpaintMillis = 0L
        private set

    fun erase(strip: Strip, box: Box): Patch {
        val scale = 1f / strip.slots[strip.pageAt(box.centerY)].scale
        val text = box.inset(-PAD, -PAD)
        val area = Box(
            (text.left - CONTEXT).coerceAtLeast(0f),
            (text.top - CONTEXT).coerceAtLeast(0f),
            (text.right + CONTEXT).coerceAtMost(Strip.WIDTH.toFloat()),
            (text.bottom + CONTEXT).coerceAtMost(strip.height),
        )
        val bitmap = strip.crop(area, scale)
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)

        val tx0 = ((text.left - area.left) * scale).toInt().coerceIn(0, w - 1)
        val ty0 = ((text.top - area.top) * scale).toInt().coerceIn(0, h - 1)
        val tx1 = ceil((text.right - area.left) * scale).toInt().coerceIn(1, w)
        val ty1 = ceil((text.bottom - area.top) * scale).toInt().coerceIn(1, h)

        val (background, _) = ring(pixels, w, h, tx0, ty0, tx1, ty1, (RING * scale).toInt().coerceAtLeast(2))
        val bgGray = gray(background)
        val strokes = BooleanArray(w * h)
        for (y in ty0 until ty1) {
            for (x in tx0 until tx1) {
                strokes[y * w + x] = abs(gray(pixels[y * w + x]) - bgGray) > STROKE_DIFF
            }
        }
        val inked = strokes.copyOf()
        dropEdgeStrokes(strokes, w, tx0, ty0, tx1, ty1)
        val mask = dilate(strokes, w, h, (GROW * scale).toInt().coerceAtLeast(1))

        // Plain filling is only safe on a clean background where the remaining dark pixels are letters (strokes
        // touching the box border are already excluded); anything else goes to the inpainting model
        val boxPixels = (tx1 - tx0) * (ty1 - ty0)
        val strokeShare = strokes.count { it }.toFloat() / boxPixels.coerceAtLeast(1)
        // What is left in the box once letters and lines entering from outside are ignored must be plain
        var rest = 0
        var textured = 0
        for (y in ty0 until ty1) {
            for (x in tx0 until tx1) {
                val i = y * w + x
                if (inked[i] || mask[i]) continue
                rest++
                if (abs(gray(pixels[i]) - bgGray) > TEXTURE_DIFF) textured++
            }
        }
        val flat = textured < rest * MAX_TEXTURED_SHARE && strokeShare < MAX_LETTER_SHARE
        if (flat) {
            for (i in pixels.indices) if (mask[i]) pixels[i] = background
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        } else {
            val start = System.currentTimeMillis()
            inpaint(bitmap, pixels, mask)
            inpainted++
            inpaintMillis += System.currentTimeMillis() - start
        }
        return Patch(area, text.inset(-GROW, -GROW), bitmap, background, flat)
    }

    private fun inpaint(bitmap: Bitmap, pixels: IntArray, mask: BooleanArray) {
        val w = bitmap.width
        val h = bitmap.height
        val plane = w * h
        val image = ByteBuffer.allocateDirect(3 * plane)
        val known = ByteBuffer.allocateDirect(plane)
        for (i in 0 until plane) {
            val p = pixels[i]
            image.put(i, (p shr 16 and 0xFF).toByte())
            image.put(plane + i, (p shr 8 and 0xFF).toByte())
            image.put(2 * plane + i, (p and 0xFF).toByte())
            known.put(i, if (mask[i]) 0 else 255.toByte())
        }
        val shape = longArrayOf(1, 3, h.toLong(), w.toLong())
        OnnxTensor.createTensor(env, image, shape, OnnxJavaType.UINT8).use { img ->
            OnnxTensor.createTensor(
                env,
                known,
                longArrayOf(1, 1, h.toLong(), w.toLong()),
                OnnxJavaType.UINT8,
            ).use { m ->
                session.run(mapOf("image" to img, "mask" to m)).use { out ->
                    val result = (out[0] as OnnxTensor).byteBuffer
                    for (i in 0 until plane) {
                        if (!mask[i]) continue
                        pixels[i] = Color.rgb(
                            result.get(i).toInt() and 0xFF,
                            result.get(plane + i).toInt() and 0xFF,
                            result.get(2 * plane + i).toInt() and 0xFF,
                        )
                    }
                }
            }
        }
        bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
    }

    /** Median colour and gray spread of the ring around the text rectangle. */
    private fun ring(
        pixels: IntArray,
        w: Int,
        h: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        size: Int,
    ): Pair<Int, Float> {
        val samples = ArrayList<Int>()
        for (y in (y0 - size).coerceAtLeast(0) until (y1 + size).coerceAtMost(h)) {
            for (x in (x0 - size).coerceAtLeast(0) until (x1 + size).coerceAtMost(w)) {
                val inside = x in x0 until x1 && y in y0 until y1
                if (!inside && (x + y) % 2 == 0) samples += pixels[y * w + x]
            }
        }
        if (samples.isEmpty()) return Color.WHITE to 0f
        val mid = samples.size / 2
        val color = Color.rgb(
            samples.map { Color.red(it) }.sorted()[mid],
            samples.map { Color.green(it) }.sorted()[mid],
            samples.map { Color.blue(it) }.sorted()[mid],
        )
        // Share of ring pixels far from the median: a bubble outline crossing the ring does not make it busy
        val medianGray = gray(color)
        val busy = samples.count { abs(gray(it) - medianGray) > STROKE_DIFF }.toFloat() / samples.size
        return color to busy
    }

    /**
     * Letters sit inside the text box, while bubble outlines and artwork lines enter it from outside: any stroke
     * touching the box border is left untouched.
     */
    private fun dropEdgeStrokes(strokes: BooleanArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        val stack = ArrayDeque<Int>()
        fun seed(x: Int, y: Int) {
            val i = y * w + x
            if (strokes[i]) {
                strokes[i] = false
                stack.addLast(i)
            }
        }
        for (x in x0 until x1) {
            seed(x, y0)
            seed(x, y1 - 1)
        }
        for (y in y0 until y1) {
            seed(x0, y)
            seed(x1 - 1, y)
        }
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % w
            val y = i / w
            if (x > x0) seed(x - 1, y)
            if (x < x1 - 1) seed(x + 1, y)
            if (y > y0) seed(x, y - 1)
            if (y < y1 - 1) seed(x, y + 1)
        }
    }

    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        // Separable square dilation
        val tmp = BooleanArray(src.size)
        for (y in 0 until h) {
            var run = -r - 1
            for (x in 0 until w) {
                if (src[y * w + x]) run = x
                tmp[y * w + x] = x - run <= r
            }
            run = w + r + 1
            for (x in w - 1 downTo 0) {
                if (src[y * w + x]) run = x
                if (run - x <= r) tmp[y * w + x] = true
            }
        }
        val out = BooleanArray(src.size)
        for (x in 0 until w) {
            var run = -r - 1
            for (y in 0 until h) {
                if (tmp[y * w + x]) run = y
                out[y * w + x] = y - run <= r
            }
            run = h + r + 1
            for (y in h - 1 downTo 0) {
                if (tmp[y * w + x]) run = y
                if (run - y <= r) out[y * w + x] = true
            }
        }
        return out
    }

    private fun gray(c: Int) = 0.299f * Color.red(c) + 0.587f * Color.green(c) + 0.114f * Color.blue(c)

    override fun close() {
        if (lazySession.isInitialized()) session.close()
    }

    private companion object {
        const val PAD = 5f
        const val CONTEXT = 60f
        const val RING = 6f
        const val GROW = 3f
        const val STROKE_DIFF = 30f
        const val TEXTURE_DIFF = 12f
        const val MAX_TEXTURED_SHARE = 0.08f
        const val MAX_LETTER_SHARE = 0.55f
    }
}
