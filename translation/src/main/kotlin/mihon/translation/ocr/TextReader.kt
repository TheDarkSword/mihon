package mihon.translation.ocr

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mihon.translation.image.Strip
import mihon.translation.model.BlockKind
import mihon.translation.model.Box
import mihon.translation.model.Detection
import mihon.translation.model.RegionLabel
import mihon.translation.model.TextBlock
import java.io.ByteArrayOutputStream
import java.io.Closeable
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Reads the text of detected regions with ML Kit (Latin script, on device). */
class TextReader(private val parallelism: Int = 4) : Closeable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(strip: Strip, detections: List<Detection>): List<TextBlock> = coroutineScope {
        val bubbles = detections.filter { it.label == RegionLabel.BUBBLE }
        val texts = detections.filter { it.label != RegionLabel.BUBBLE }
        val permits = Semaphore(parallelism)
        val read = texts.map { det ->
            async {
                permits.withPermit {
                    val owner = bubbles.firstOrNull { it.box.covers(det.box) >= 0.6f }
                    val (text, confidence) = recognize(strip, det.box, retry = !det.weak)
                    val letters = text.count { it.isLetter() }
                    if (det.weak && letters < MIN_WEAK_LETTERS) return@withPermit null
                    TextBlock(
                        id = 0,
                        kind = if (det.label == RegionLabel.TEXT_BUBBLE) BlockKind.BUBBLE else BlockKind.FREE,
                        box = det.box,
                        bubble = owner?.box,
                        page = strip.pageAt(det.box.centerY),
                        text = text,
                        confidence = confidence,
                        vision = letters == 0,
                    )
                }
            }
        }.awaitAll()
        read.filterNotNull().mapIndexed { i, block -> block.copy(id = i + 1) }
    }

    private suspend fun recognize(strip: Strip, box: Box, retry: Boolean): Pair<String, Float> {
        val padded = Box(
            (box.left - PAD).coerceAtLeast(0f),
            (box.top - PAD).coerceAtLeast(0f),
            (box.right + PAD).coerceAtMost(Strip.WIDTH.toFloat()),
            (box.bottom + PAD).coerceAtMost(strip.height),
        )
        // Native page resolution, upscaled further when the text is small
        val native = 1f / strip.slots[strip.pageAt(box.centerY)].scale
        val scale = if (minOf(padded.width, padded.height) * native < MIN_SIDE) native * 2 else native
        val first = strip.crop(padded, scale).use { recognize(it) }
        if (first.first.isNotBlank() || !retry) return first
        // Heavy or italic lettering is often missed: retry larger, in high contrast grayscale
        return strip.crop(padded, scale * 2).use { crop -> highContrast(crop).use { recognize(it) } }
    }

    private suspend fun recognize(bitmap: Bitmap): Pair<String, Float> {
        val result = suspendCancellableCoroutine { cont ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val lines = result.textBlocks.flatMap { it.lines }.sortedBy { it.boundingBox?.top ?: 0 }
        val text = lines.joinToString(" ") { it.text }.trim()
        val confidence = lines.map { it.confidence }.average().takeIf { !it.isNaN() }?.toFloat() ?: 0f
        return text to confidence
    }

    private fun highContrast(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val contrast = floatArrayOf(
            2f, 0f, 0f, 0f, -255f,
            0f, 2f, 0f, 0f, -255f,
            0f, 0f, 2f, 0f, -255f,
            0f, 0f, 0f, 1f, 0f,
        )
        val matrix = ColorMatrix().apply {
            setSaturation(0f)
            postConcat(ColorMatrix(contrast))
        }
        Canvas(out).drawBitmap(source, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        return out
    }

    private inline fun <T> Bitmap.use(block: (Bitmap) -> T): T = try {
        block(this)
    } finally {
        recycle()
    }

    override fun close() {
        recognizer.close()
    }

    companion object {
        private const val PAD = 6f
        private const val MIN_SIDE = 80f
        private const val MIN_WEAK_LETTERS = 3
        private const val VISION_SIDE = 512f

        /** JPEG of a region at page resolution, longest side capped, for the model to read. */
        fun visionCrop(strip: Strip, box: Box): ByteArray {
            val native = 1f / strip.slots[strip.pageAt(box.centerY)].scale
            val scale = minOf(native, VISION_SIDE / maxOf(box.width, box.height))
            val bitmap = strip.crop(box.inset(-PAD, -PAD), scale)
            return try {
                ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
            } finally {
                bitmap.recycle()
            }
        }
    }
}
