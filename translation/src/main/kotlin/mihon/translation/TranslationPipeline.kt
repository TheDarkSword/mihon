package mihon.translation

import android.content.Context
import android.graphics.BitmapRegionDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import logcat.LogPriority
import logcat.logcat
import mihon.translation.context.SeriesContext
import mihon.translation.detect.TextDetector
import mihon.translation.image.Strip
import mihon.translation.llm.GeminiClient
import mihon.translation.model.Detection
import mihon.translation.model.TextBlock
import mihon.translation.model.TranslatedLine
import mihon.translation.ocr.TextReader
import mihon.translation.render.Typesetter
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

@Serializable
data class ChapterTranslation(
    val version: Int = 1,
    val series: String,
    val chapter: Float,
    val model: String,
    val blocks: List<TextBlock>,
    val lines: List<TranslatedLine>,
)

data class PipelineTimings(
    val pages: Int,
    val stripHeight: Int,
    val detections: Int,
    val blocks: Int,
    val detectMillis: Long,
    val ocrMillis: Long,
    val translation: TranslationStats,
    val renderMillis: Long,
    val totalMillis: Long,
    val resumedFrom: String?,
)

/** Detect -> OCR -> translate -> review -> typeset -> CBZ, for one chapter, resuming from checkpoints. */
class TranslationPipeline(
    private val context: Context,
    apiKey: () -> String?,
    onCall: (model: String, status: Int) -> Unit = { _, _ -> },
    reviewModels: () -> List<String> = { ChapterTranslator.FAST_REVIEW },
) {

    private val translator = ChapterTranslator(GeminiClient(apiKey, onCall), reviewModels = reviewModels)
    val cache = CheckpointCache(File(context.cacheDir, "translation")).also { it.purgeExpired() }

    suspend fun run(
        key: String,
        input: File,
        output: File,
        ctx: SeriesContext,
        chapter: Float,
        keepCheckpoints: Boolean = false,
        progress: (String) -> Unit,
    ): Pair<SeriesContext, PipelineTimings> = withContext(Dispatchers.Default) {
        val start = System.currentTimeMillis()
        val checkpoint = cache.entry(key)
        var resumedFrom: String? = null
        openStrip(input).use { strip ->
            val detections = checkpoint.load(STAGE_DETECT, ListSerializer(Detection.serializer()))
                ?.also { resumedFrom = STAGE_DETECT }
                ?: run {
                    progress("Detecting text on ${strip.slots.size} pages")
                    TextDetector(context).use { detector ->
                        detector.detect(strip) { i, n -> if (i % 20 == 0) progress("Detecting: window $i/$n") }
                    }.also { checkpoint.save(STAGE_DETECT, ListSerializer(Detection.serializer()), it) }
                }
            val detected = System.currentTimeMillis()

            val rawBlocks = checkpoint.load(STAGE_OCR, ListSerializer(TextBlock.serializer()))
                ?.also { resumedFrom = STAGE_OCR }
                ?: run {
                    progress("Reading ${detections.size} regions")
                    TextReader().use { it.read(strip, detections) }
                        .also { checkpoint.save(STAGE_OCR, ListSerializer(TextBlock.serializer()), it) }
                }
            val read = System.currentTimeMillis()
            val blocks = dedupe(rawBlocks)

            val draft = checkpoint.load(STAGE_DRAFT, ChapterTranslator.Draft.serializer())
                ?.also { resumedFrom = STAGE_DRAFT }
                ?: run {
                    progress("Translating ${blocks.size} lines")
                    val scores = blocks.associate { b -> b.id to detections.firstOrNull { it.box == b.box }?.score }
                    val crops = blocks.filter { ChapterTranslator.needsCrop(it, scores[it.id]) }
                        .sortedBy { it.confidence }
                        .take(MAX_VISION_CROPS)
                        .associate { it.id to TextReader.visionCrop(strip, it.box) }
                    progress("Sending ${crops.size} crops for the model to read")
                    translator.draft(ctx, chapter, blocks, crops)
                        .also { checkpoint.save(STAGE_DRAFT, ChapterTranslator.Draft.serializer(), it) }
                }

            val reviewed = checkpoint.load(STAGE_REVIEW, ChapterTranslator.Reviewed.serializer())
                ?.also { resumedFrom = STAGE_REVIEW }
                ?: run {
                    progress("Reviewing")
                    translator.review(ctx, chapter, draft).also {
                        // A review skipped for overload is retried on the next run
                        if (it.error == null) checkpoint.save(STAGE_REVIEW, ChapterTranslator.Reviewed.serializer(), it)
                    }
                }
            val result = translator.finish(ctx, chapter, blocks, draft, reviewed)
            val byId = result.lines.associateBy { it.id }

            progress("Typesetting")
            val renderStart = System.currentTimeMillis()
            val placements = blocks.mapNotNull { b -> byId[b.id]?.let { Typesetter.Placement(b, it.it) } }
            val data = ChapterTranslation(
                series = ctx.series,
                chapter = chapter,
                model = "gemini-3.5-flash-lite",
                blocks = blocks,
                lines = result.lines,
            )
            output.parentFile?.mkdirs()
            val tmp = File(output.path + ".tmp")
            ZipOutputStream(tmp.outputStream().buffered()).use { zip ->
                Typesetter(context).render(strip, placements) { name, bytes -> zip.putStored(name, bytes) }
                zip.putStored(JSON_ENTRY, json.encodeToString(data).toByteArray())
            }
            tmp.renameTo(output)
            if (!keepCheckpoints) checkpoint.delete()
            val end = System.currentTimeMillis()

            val timings = PipelineTimings(
                pages = strip.slots.size,
                stripHeight = strip.height.toInt(),
                detections = detections.size,
                blocks = blocks.size,
                detectMillis = detected - start,
                ocrMillis = read - detected,
                translation = result.stats,
                renderMillis = end - renderStart,
                totalMillis = end - start,
                resumedFrom = resumedFrom,
            )
            logcat(LogPriority.INFO, TAG) { "Chapter $chapter: $timings" }
            result.context to timings
        }
    }

    /** Detection only, with the given settings, to compare speed on the device without using any quota. */
    suspend fun benchmarkDetector(input: File, threads: Int, xnnpack: Boolean, overlap: Float): String =
        withContext(Dispatchers.Default) {
            openStrip(input).use { strip ->
                val t0 = System.currentTimeMillis()
                val found = TextDetector(context, threads, xnnpack, overlap).use { it.detect(strip) }
                val ms = System.currentTimeMillis() - t0
                "threads=$threads xnnpack=$xnnpack overlap=$overlap: ${ms / 1000.0}s, ${found.size} regions"
            }
        }

    /** Thin slices of a line are sometimes detected on their own: keep only the larger block. */
    private fun dedupe(blocks: List<TextBlock>): List<TextBlock> = blocks.filter { b ->
        blocks.none { other -> other !== b && other.box.area > b.box.area && other.box.covers(b.box) >= 0.5f }
    }

    private fun openStrip(input: File): Strip {
        val pages = if (input.isDirectory) {
            input.listFiles { f -> f.extension.lowercase() in IMAGE_EXTENSIONS }!!
                .sortedBy { it.name }
                .map { file -> Strip.PageImage(file.name) { BitmapRegionDecoder.newInstance(file.path) } }
        } else {
            val zip = ZipFile(input)
            zip.entries().toList()
                .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS }
                .sortedBy { it.name }
                .map { entry ->
                    Strip.PageImage(entry.name) {
                        val bytes = zip.getInputStream(entry).use { it.readBytes() }
                        BitmapRegionDecoder.newInstance(bytes, 0, bytes.size)
                    }
                }
        }
        return Strip(pages)
    }

    private fun ZipOutputStream.putStored(name: String, bytes: ByteArray) {
        val entry = ZipEntry(name).apply {
            method = ZipEntry.STORED
            size = bytes.size.toLong()
            compressedSize = bytes.size.toLong()
            crc = CRC32().also { it.update(bytes) }.value
        }
        putNextEntry(entry)
        write(bytes)
        closeEntry()
    }

    companion object {
        const val TAG = "MihonTranslation"
        const val JSON_ENTRY = "mihon-translation.json"
        private const val MAX_VISION_CROPS = 12
        private const val STAGE_DETECT = "detections"
        private const val STAGE_OCR = "blocks"
        private const val STAGE_DRAFT = "draft"
        private const val STAGE_REVIEW = "review"
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
        val json = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}
