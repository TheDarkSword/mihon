package mihon.translation.detect

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.graphics.Bitmap
import mihon.translation.image.Strip
import mihon.translation.model.Box
import mihon.translation.model.Detection
import mihon.translation.model.RegionLabel
import mihon.translation.model.TextDetectorThresholds
import java.io.Closeable
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.sqrt

/**
 * Bubble and text detector (ogkalu comic-text-and-bubble-detector, RT-DETR v2) run on overlapping square
 * windows of the strip.
 */
class TextDetector(
    context: Context,
    threads: Int = 4,
    xnnpack: Boolean = false,
    private val overlap: Float = WINDOW_OVERLAP,
) : Closeable {

    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = run {
        val model = context.assets.open("translation/detector.onnx").use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            if (xnnpack) addXnnpack(mapOf("intra_op_num_threads" to threads.toString()))
        }
        env.createSession(model, options)
    }

    fun detect(strip: Strip, onWindow: (Int, Int) -> Unit = { _, _ -> }): List<Detection> {
        // The model was trained on images resized without cropping, so taller windows squashed to a square
        // keep recall while needing far fewer runs
        val width = Strip.WIDTH.toFloat()
        val window = width * WINDOW_ASPECT
        val step = window * (1 - overlap)
        val tops = generateSequence(0f) { it + step }.takeWhile { it + window < strip.height }.toMutableList()
        if (tops.isEmpty() || tops.last() + window < strip.height) tops += (strip.height - window).coerceAtLeast(0f)

        val input = FloatBuffer.allocate(3 * INPUT * INPUT)
        val pixels = IntArray(INPUT * INPUT)
        val found = mutableListOf<Detection>()
        tops.forEachIndexed { index, top ->
            onWindow(index, tops.size)
            val box = Box(0f, top, width, minOf(top + window, strip.height))
            val tile = strip.crop(box, INPUT / width)
            val scaled = if (tile.height != INPUT) Bitmap.createScaledBitmap(tile, INPUT, INPUT, true) else tile
            scaled.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
            if (scaled !== tile) scaled.recycle()
            tile.recycle()
            if (isFlat(pixels)) return@forEachIndexed

            fillInput(pixels, input)
            val sizes = LongBuffer.wrap(longArrayOf(width.toLong(), box.height.toLong()))
            OnnxTensor.createTensor(env, input, longArrayOf(1, 3, INPUT.toLong(), INPUT.toLong())).use { image ->
                OnnxTensor.createTensor(env, sizes, longArrayOf(1, 2)).use { size ->
                    session.run(mapOf("images" to image, "orig_target_sizes" to size)).use { out ->
                        @Suppress("UNCHECKED_CAST")
                        val labels = (out[0].value as Array<LongArray>)[0]

                        @Suppress("UNCHECKED_CAST")
                        val boxes = (out[1].value as Array<Array<FloatArray>>)[0]

                        @Suppress("UNCHECKED_CAST")
                        val scores = (out[2].value as Array<FloatArray>)[0]
                        for (i in labels.indices) {
                            if (scores[i] < TextDetectorThresholds.MIN) continue
                            val (x0, y0, x1, y1) = boxes[i]
                            found += Detection(
                                label = RegionLabel.entries[labels[i].toInt()],
                                score = scores[i],
                                box = Box(x0, y0 + top, x1, y1 + top),
                                touchesEdge = y0 < EDGE || y1 > box.height - EDGE,
                            )
                        }
                    }
                }
            }
        }
        return merge(found)
    }

    private fun fillInput(pixels: IntArray, input: FloatBuffer) {
        input.clear()
        val plane = INPUT * INPUT
        val data = input.array()
        for (i in pixels.indices) {
            val p = pixels[i]
            data[i] = (p shr 16 and 0xFF) / 255f
            data[plane + i] = (p shr 8 and 0xFF) / 255f
            data[2 * plane + i] = (p and 0xFF) / 255f
        }
    }

    private fun isFlat(pixels: IntArray): Boolean {
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (i in pixels.indices step 7) {
            val p = pixels[i]
            val v = ((p shr 16 and 0xFF) + (p shr 8 and 0xFF) + (p and 0xFF)) / 3.0
            sum += v
            sumSq += v * v
            n++
        }
        val mean = sum / n
        return sqrt(sumSq / n - mean * mean) < FLAT_STD
    }

    override fun close() {
        session.close()
    }

    companion object {
        private const val INPUT = 640
        private const val WINDOW_ASPECT = 1.5f
        private const val WINDOW_OVERLAP = 0.2f
        private const val FLAT_STD = 4.0
        private const val EDGE = 4f

        /** Whole boxes win over boxes cut by a window edge, then the highest score; cut pieces are grown together. */
        fun merge(boxes: List<Detection>): List<Detection> {
            val kept = mutableListOf<Detection>()
            for (b in boxes.sortedWith(compareBy<Detection> { it.touchesEdge }.thenByDescending { it.score })) {
                val index = kept.indexOfFirst {
                    sameKind(it.label, b.label) &&
                        (it.box.iou(b.box) > 0.4f || it.box.covers(b.box) >= 0.8f || b.box.covers(it.box) >= 0.8f)
                }
                if (index < 0) {
                    kept += b
                    continue
                }
                val dup = kept[index]
                kept[index] = when {
                    !b.touchesEdge && dup.touchesEdge -> b
                    dup.touchesEdge || b.touchesEdge -> dup.copy(box = dup.box.union(b.box))
                    else -> dup
                }
            }
            return kept.sortedWith(compareBy({ it.box.top }, { it.box.left }))
        }

        /** The same lettering is often reported both as bubble text and as free text. */
        private fun sameKind(a: RegionLabel, b: RegionLabel) =
            a == b || (a != RegionLabel.BUBBLE && b != RegionLabel.BUBBLE)
    }
}
