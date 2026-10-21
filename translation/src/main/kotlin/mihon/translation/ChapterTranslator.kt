package mihon.translation

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.translation.context.Address
import mihon.translation.context.ChapterSummary
import mihon.translation.context.Character
import mihon.translation.context.ContextBudget
import mihon.translation.context.ContextView
import mihon.translation.context.GlossaryEntry
import mihon.translation.context.PhraseEntry
import mihon.translation.context.SeriesContext
import mihon.translation.context.Tail
import mihon.translation.context.TailLine
import mihon.translation.context.label
import mihon.translation.llm.GeminiClient
import mihon.translation.llm.LlmException
import mihon.translation.llm.Prompts
import mihon.translation.model.BlockKind
import mihon.translation.model.TextBlock
import mihon.translation.model.TranslatedLine
import kotlin.math.roundToInt

/** Models whose free daily quota ran out, forgotten when the quota resets at midnight Pacific time. */
class DailyQuota {
    private val zone = java.time.ZoneId.of("America/Los_Angeles")
    private var day = java.time.LocalDate.now(zone)
    private val models = mutableSetOf<String>()

    @Synchronized
    fun isExhausted(model: String): Boolean {
        val today = java.time.LocalDate.now(zone)
        if (today != day) {
            day = today
            models.clear()
        }
        return model in models
    }

    @Synchronized
    fun mark(model: String) {
        models += model
    }
}

@Serializable
data class TranslationStats(
    val lines: Int,
    val skipped: Int,
    val inputTokens: Int,
    val outputTokens: Int,
    val translateMillis: Long,
    val reviewMillis: Long,
    val reviewFixes: Int,
    val namesFixed: Int,
    val glitches: Int,
    val reviewError: String?,
    val reviewModel: String? = null,
)

class ChapterTranslator(
    private val client: GeminiClient,
    private val model: String = "gemini-3.5-flash-lite",
    private val reviewModels: () -> List<String> = { FAST_REVIEW },
) {

    data class Result(val lines: List<TranslatedLine>, val context: SeriesContext, val stats: TranslationStats)

    /** First pass: one request translating the whole chapter, plus the model's context update. */
    @Serializable
    data class Draft(
        val lines: List<TranslatedLine>,
        val update: JsonObject,
        val overflow: List<ChapterSummary>,
        val inputTokens: Int,
        val outputTokens: Int,
        val millis: Long,
        val skipped: Int,
    )

    @Serializable
    data class Reviewed(
        val lines: List<TranslatedLine>,
        val fixes: Int,
        val millis: Long,
        val error: String?,
        val model: String? = null,
    )

    suspend fun draft(
        ctx: SeriesContext,
        chapter: Float,
        blocks: List<TextBlock>,
        crops: Map<Int, ByteArray> = emptyMap(),
    ): Draft {
        val (keep, skipped) = blocks.partition { it.id in crops || !isSkippable(it, ctx.series) }
        val view = ContextBudget.forChapter(ctx, chapter)
        val overflow = ContextBudget.storyOverflow(ctx)
        val prompt = buildString {
            append(ContextBudget.render(view))
            appendLine("# Chapter ${chapter.label()}")
            appendLine("Lines as id|region|page|max length|text:")
            keep.forEach {
                val text = when {
                    it.id !in crops -> it.text
                    it.text.isBlank() -> "[unreadable, see image for line ${it.id}]"
                    else -> "${it.text} [OCR unsure, check image for line ${it.id}]"
                }
                appendLine("${it.id}|${it.kind.name.lowercase()}|p${it.page}|max ${maxLength(it.text)}|$text")
            }
            if (crops.isNotEmpty()) {
                appendLine()
                appendLine("Some lines are unreadable or unsure in OCR: read them from the attached images, put the")
                appendLine(
                    "English you read in \"en\" and translate that. If an image holds no real text, use type noise.",
                )
            }
            if (overflow.isNotEmpty()) {
                append(Prompts.COMPACT)
                overflow.forEach { append("\n- Ch. ${it.n.label()}: ${it.summary}") }
            }
        }
        val images = keep.mapNotNull { b -> crops[b.id]?.let { "Image for line ${b.id}:" to it } }
        val result = client.generate(model, Prompts.TRANSLATE_SYSTEM, prompt, Prompts.TRANSLATE_SCHEMA, images = images)
        val byId = result.json["lines"]!!.jsonArray.associate {
            it.jsonObject["id"]!!.jsonPrimitive.int to it.jsonObject
        }
        val lines = keep.map { b ->
            val o = byId[b.id]
            val en = if (b.id in crops) o?.str("en")?.ifBlank { null } ?: b.text else b.text
            TranslatedLine(b.id, en, o?.str("it").orEmpty(), o?.get("type")?.jsonPrimitive?.contentOrNull ?: "missing")
        }
        return Draft(
            lines = lines,
            update = result.json["context_update"]!!.jsonObject,
            overflow = overflow,
            inputTokens = result.inputTokens,
            outputTokens = result.outputTokens,
            millis = result.millis,
            skipped = skipped.size,
        )
    }

    /** Second pass on another quota pool when possible. Temporary failures keep the draft instead of failing. */
    suspend fun review(ctx: SeriesContext, chapter: Float, draft: Draft): Reviewed {
        val view = ContextBudget.forChapter(ctx, chapter)
        val lines = draft.lines.map { it.copy(it = enforceNames(it.it, view).first) }
        var error: String? = null
        for (reviewModel in reviewModels().filterNot { exhausted.isExhausted(it) }) {
            try {
                val (out, fixes, millis) = review(reviewModel, view, chapter, lines)
                return Reviewed(out, fixes, millis, null, reviewModel)
            } catch (e: LlmException) {
                if (!e.isTemporary) throw e
                if (e.status == 429 && e.message.orEmpty().contains("PerDay", ignoreCase = true)) {
                    exhausted.mark(reviewModel)
                }
                error = "$reviewModel: ${e.status}"
            }
        }
        return Reviewed(lines, 0, 0, error)
    }

    fun finish(ctx: SeriesContext, chapter: Float, blocks: List<TextBlock>, draft: Draft, reviewed: Reviewed): Result {
        val view = ContextBudget.forChapter(ctx, chapter)
        var namesFixed = 0
        val lines = reviewed.lines.map { l ->
            val (text, n) = enforceNames(l.it, view)
            namesFixed += n
            l.copy(it = text)
        }
        val keptIds = draft.lines.map { it.id }.toSet()
        val dialogue = lines.filter { it.type in setOf("dialogue", "thought", "narration") && it.it.isNotBlank() }
        val updated = applyUpdate(
            ctx,
            chapter,
            draft.update,
            blocks.filter { it.id in keptIds }.joinToString("\n") { it.text },
            dialogue,
            draft.overflow,
        )
        val stats = TranslationStats(
            lines = draft.lines.size,
            skipped = draft.skipped,
            inputTokens = draft.inputTokens,
            outputTokens = draft.outputTokens,
            translateMillis = draft.millis,
            reviewMillis = reviewed.millis,
            reviewFixes = reviewed.fixes,
            namesFixed = namesFixed,
            glitches = lines.count { hasGlitch(it.it) },
            reviewError = reviewed.error,
            reviewModel = reviewed.model,
        )
        return Result(lines, updated, stats)
    }

    private suspend fun review(
        model: String,
        view: ContextView,
        chapter: Float,
        lines: List<TranslatedLine>,
    ): Triple<List<TranslatedLine>, Int, Long> {
        val reviewView = view.copy(arcSummary = "", chapters = emptyList(), tm = emptyList(), tail = null)
        val prompt = buildString {
            append(ContextBudget.render(reviewView))
            appendLine("# Chapter ${chapter.label()}")
            lines.filter { it.it.isNotBlank() }.forEach {
                appendLine("${it.id}. EN: ${it.en}")
                appendLine("   IT: ${it.it}")
            }
        }
        // One attempt per model: an overloaded reviewer just hands over to the next one
        val result = client.generate(
            model,
            Prompts.REVIEW_SYSTEM,
            prompt,
            Prompts.REVIEW_SCHEMA,
            temperature = 0.2,
            attempts = 1,
            // Full Flash models think before answering and can take minutes when busy
            timeoutSeconds = if ("lite" in model) REVIEW_TIMEOUT_SECONDS else QUALITY_REVIEW_TIMEOUT_SECONDS,
        )
        val fixes = result.json["fixes"]!!.jsonArray.associate {
            it.jsonObject["id"]!!.jsonPrimitive.int to it.jsonObject.str("it").trim()
        }
        var count = 0
        val out = lines.map { l ->
            val fix = fixes[l.id]
            if (fix.isNullOrBlank() || fix == l.it || l.it.isBlank()) l else l.copy(it = fix).also { count++ }
        }
        return Triple(out, count, result.millis)
    }

    private fun applyUpdate(
        ctx: SeriesContext,
        chapter: Float,
        update: JsonObject,
        sourceText: String,
        translated: List<TranslatedLine>,
        overflow: List<ChapterSummary>,
    ): SeriesContext {
        val text = sourceText.lowercase()
        val glossary = ctx.glossary.associateBy { key(it.en) }.toMutableMap()
        update.array("glossary_add").forEach { g ->
            val k = key(g.str("en"))
            if (k.isEmpty() || k in glossary) return@forEach
            glossary[k] = GlossaryEntry(
                en = g.str("en"),
                it = g.str("it"),
                kind = g.str("kind").ifEmpty { "term" },
                keep = g["keep"]?.jsonPrimitive?.booleanOrNull ?: false,
                note = g["note"]?.jsonPrimitive?.contentOrNull,
                firstSeen = chapter,
                lastSeen = chapter,
            )
        }
        val glossaryOut = glossary.values.map {
            if (key(it.en) in text) it.copy(lastSeen = maxOf(it.lastSeen, chapter), hits = it.hits + 1) else it
        }

        val characters = ctx.characters.associateBy { key(it.name) }.toMutableMap()
        update.array("characters_update").forEach { c ->
            val name = c.str("name")
            val k = key(name)
            if (k.isEmpty()) return@forEach
            val addresses = c.array("addresses").map { Address(it.str("to"), it.str("form")) }
            val current = characters[k]
            characters[k] = when {
                current == null -> Character(
                    name = name,
                    gender = c.str("gender").ifEmpty { null },
                    role = c.str("role").ifEmpty { null },
                    speech = c.str("speech").ifEmpty { null },
                    addresses = addresses,
                    firstSeen = chapter,
                    lastSeen = chapter,
                    hits = 1,
                )
                current.locked -> current.copy(lastSeen = chapter, hits = current.hits + 1)
                else -> {
                    val known = current.addresses.map { key(it.to) }.toSet()
                    current.copy(
                        gender = current.gender.takeUnless { it.isNullOrBlank() || it == "unknown" }
                            ?: c.str("gender").ifEmpty { null },
                        role = current.role ?: c.str("role").ifEmpty { null },
                        speech = current.speech ?: c.str("speech").ifEmpty { null },
                        addresses = current.addresses + addresses.filter { key(it.to) !in known },
                        lastSeen = chapter,
                        hits = current.hits + 1,
                    )
                }
            }
        }

        val tm = ctx.tm.associateBy { key(it.en) }.toMutableMap()
        update.array("tm_add").forEach { e ->
            val k = key(e.str("en"))
            if (k.isNotEmpty() && k !in tm) {
                tm[k] = PhraseEntry(e.str("en"), e.str("it"), firstSeen = chapter, lastSeen = chapter, hits = 1)
            }
        }

        var chapters = ctx.chapters.filter { it.n != chapter }
        update.str("chapter_summary").takeIf { it.isNotBlank() }?.let {
            chapters =
                chapters + ChapterSummary(chapter, it)
        }
        chapters = chapters.sortedBy { it.n }
        var arcSummary = ctx.arcSummary
        var arcUntil = ctx.arcUntil
        update.str("arc_summary").takeIf { it.isNotBlank() && overflow.isNotEmpty() }?.let { arc ->
            val folded = overflow.map { it.n }.toSet()
            arcSummary = arc
            arcUntil = folded.max()
            chapters = chapters.filter { it.n !in folded }
        }

        val tail = translated.takeLast(ContextBudget.TAIL_LINES)
            .takeIf { it.isNotEmpty() }
            ?.let { lines -> Tail(chapter, lines.map { TailLine(it.en, it.it) }) }

        return ctx.copy(
            version = ctx.version + 1,
            glossary = glossaryOut,
            characters = characters.values.toList(),
            arcSummary = arcSummary,
            arcUntil = arcUntil,
            chapters = chapters,
            tm = tm.values.toList(),
            tail = tail ?: ctx.tail,
        )
    }

    companion object {
        private val SKIP = Regex(
            """(discord\.gg|t\.me/|telegram|asurascans|asura\s*scans|patreon|ko-?fi|read (it )?(at|on)|\.com\b|join us)""",
            RegexOption.IGNORE_CASE,
        )

        /** Scanlation credits such as "TL GRAY", "QC SCARPET", "PR BADWOLF". */
        private val CREDIT = Regex("""^(TL|TS|PR|QC|RD|CL|ED|RAW|RP|TLC)\b[\s:]+\S+.{0,20}$""")
        private const val REVIEW_TIMEOUT_SECONDS = 40L
        private const val QUALITY_REVIEW_TIMEOUT_SECONDS = 240L

        // 3.1 often answers 503 only after ~25 s, so it is just the fallback for when 3.5 runs out of quota
        val FAST_REVIEW = listOf("gemini-3.5-flash-lite", "gemini-3.1-flash-lite")

        /**
         * Full Flash models fix far more (forms of address, titles, unnatural phrasing) but have about 20 free
         * requests a day each: they are used in turn, then the fast reviewers take over.
         */
        // Fastest first; 2.x Flash models are no longer served to new API keys
        val QUALITY_REVIEW = listOf("gemini-3.6-flash", "gemini-3-flash-preview", "gemini-3.8-flash") + FAST_REVIEW

        private val exhausted = DailyQuota()

        /** ML Kit line confidences sit around 0.6-0.8 even for clean lettering. */
        private const val MIN_CONFIDENCE = 0.35f
        private val NON_LATIN = Regex("""[^\p{IsLatin}\p{IsCommon}\p{IsInherited}]""")

        fun isSkippable(block: TextBlock, series: String): Boolean {
            val t = block.text.trim()
            return SKIP.containsMatchIn(t) ||
                CREDIT.matches(t) ||
                t.none { it.isLetter() } ||
                t.equals(series, ignoreCase = true) ||
                (block.confidence < MIN_CONFIDENCE && t.length < 12)
        }

        /** Lines worth showing to the model as images: unreadable or unsure bubbles, confident free text. */
        fun needsCrop(block: TextBlock, detectionScore: Float?): Boolean = when {
            block.kind == BlockKind.BUBBLE -> block.vision || block.confidence < UNSURE_CONFIDENCE
            block.vision -> (detectionScore ?: 0f) >= FREE_CROP_SCORE
            else -> block.confidence < UNSURE_CONFIDENCE && block.text.count { it.isLetter() } >= 3
        }

        private const val UNSURE_CONFIDENCE = 0.6f
        private const val FREE_CROP_SCORE = 0.6f

        fun maxLength(text: String): Int = (text.length * 1.15).roundToInt() + 8

        /** Rewrites English spellings of glossary names the model left in the Italian text. */
        fun enforceNames(text: String, view: ContextView): Pair<String, Int> {
            var out = text
            var fixed = 0
            view.glossary
                .filter { it.kind in NAME_KINDS && !it.keep && !it.it.equals(it.en, ignoreCase = true) }
                .forEach { g ->
                    val replaced = Regex("""\b${Regex.escape(g.en)}\b""").replace(out, g.it)
                    if (replaced != out) {
                        out = replaced
                        fixed++
                    }
                }
            return out to fixed
        }

        /** Model glitches seen in practice: other scripts or stray code-like tokens in an Italian line. */
        fun hasGlitch(text: String): Boolean = NON_LATIN.containsMatchIn(text) || STRAY.containsMatchIn(text)

        private val STRAY = Regex("""\b[a-z]+[A-Z][a-zA-Z]+\b""")
        private val NAME_KINDS = setOf("person", "place", "organization")

        private fun key(s: String) = s.trim().lowercase().replace(Regex("""\s+"""), " ")

        private fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

        private fun JsonObject.array(name: String) = this[name]?.jsonArray?.map { it.jsonObject }.orEmpty()
    }
}
