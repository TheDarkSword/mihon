package mihon.translation.context

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.translation.detect.TextDetector
import mihon.translation.image.Strip
import mihon.translation.llm.GeminiClient
import mihon.translation.llm.LlmException
import mihon.translation.ocr.TextReader
import java.io.Closeable

/** The lettering of one chapter, in reading order. */
@kotlinx.serialization.Serializable
data class ChapterText(val number: Float, val lines: List<String>)

/** Detection and OCR only, for chapters that are read but not translated. */
class TextExtractor(context: Context) : Closeable {
    private val detector = TextDetector(context)
    private val reader = TextReader()

    suspend fun read(number: Float, pages: List<Strip.PageImage>): ChapterText = Strip(pages).use { strip ->
        val blocks = reader.read(strip, detector.detect(strip))
        ChapterText(number, blocks.filter { it.text.isNotBlank() && it.confidence >= 0.35f }.map { it.text })
    }

    override fun close() {
        detector.close()
        reader.close()
    }
}

/**
 * Builds the series context up to a chapter from chapters the reader already read: the same chapters in
 * English and in the Italian scanlation teach the Italian names and terms, Italian chapters tell the story.
 */
class ContextBuilder(
    private val client: GeminiClient,
    private val models: List<String> = listOf("gemini-3.6-flash", "gemini-3-flash-preview", "gemini-3.5-flash-lite"),
) {

    suspend fun build(
        base: SeriesContext,
        until: Float,
        aligned: List<Pair<ChapterText, ChapterText>>,
        italian: List<ChapterText>,
        progress: (String) -> Unit,
    ): SeriesContext {
        var glossary = base.glossary.associateBy { it.en.lowercase() }
        var characters = base.characters.associateBy { it.name.lowercase() }
        var phrases = base.tm.associateBy { it.en.lowercase() }

        if (aligned.isNotEmpty()) {
            progress("Matching English and Italian terms")
            val prompt = buildString {
                appendLine("# Series: ${base.series}")
                aligned.forEach { (en, it) ->
                    appendLine("## Chapter ${en.number.label()} - English")
                    en.lines.forEach(::appendLine)
                    appendLine("## Chapter ${it.number.label()} - Italian")
                    it.lines.forEach(::appendLine)
                }
            }
            val json = generate(ALIGN_SYSTEM, prompt, ALIGN_SCHEMA)
            json.array("glossary").forEach { g ->
                val key = g.str("en").lowercase()
                // The reader's Italian scanlation wins over forms chosen by the model, unless the user locked them
                if (key.isNotEmpty() && glossary[key]?.locked != true) {
                    glossary =
                        glossary + (
                            key to GlossaryEntry(
                                g.str("en"),
                                g.str("it"),
                                g.str("kind").ifEmpty { "term" },
                                g["keep"]?.jsonPrimitive?.booleanOrNull ?: false,
                            )
                            )
                }
            }
            json.array("phrases").forEach { p ->
                val key = p.str("en").lowercase()
                if (key.isNotEmpty() &&
                    key !in phrases
                ) {
                    phrases = phrases + (key to PhraseEntry(p.str("en"), p.str("it")))
                }
            }
            characters = mergeCharacters(characters, json.array("characters"))
        }

        val summaries = base.chapters.associateBy { it.n }.toMutableMap()
        italian.chunked(CHAPTERS_PER_REQUEST).forEachIndexed { index, batch ->
            progress(
                "Reading the story (${index + 1}/${(italian.size + CHAPTERS_PER_REQUEST - 1) / CHAPTERS_PER_REQUEST})",
            )
            val prompt = buildString {
                appendLine("# Series: ${base.series}")
                if (characters.isNotEmpty()) {
                    appendLine("Known characters: " + characters.values.joinToString { it.name })
                }
                batch.forEach { chapter ->
                    appendLine("## Chapter ${chapter.number.label()}")
                    chapter.lines.forEach(::appendLine)
                }
            }
            val json = generate(STORY_SYSTEM, prompt, STORY_SCHEMA)
            json.array("summaries").forEach { s ->
                val n = s["n"]?.jsonPrimitive?.float ?: return@forEach
                summaries[n] = ChapterSummary(n, s.str("summary"))
            }
            characters = mergeCharacters(characters, json.array("characters"))
        }

        val ordered = summaries.values.filter { it.n <= until }.sortedBy { it.n }
        val recent = ordered.takeLast(ContextBudget.RECENT_SUMMARIES)
        val older = ordered.dropLast(ContextBudget.RECENT_SUMMARIES)
        var arc = base.arcSummary
        if (older.isNotEmpty()) {
            progress("Summarising the story so far")
            val prompt = buildString {
                if (arc.isNotBlank()) appendLine("Story so far: $arc")
                older.forEach { appendLine("- Ch. ${it.n.label()}: ${it.summary}") }
            }
            arc = generate(ARC_SYSTEM, prompt, ARC_SCHEMA).str("arc_summary").ifBlank { arc }
        }

        return base.copy(
            version = base.version + 1,
            glossary = glossary.values.toList(),
            characters = characters.values.toList(),
            arcSummary = arc,
            arcUntil = if (older.isNotEmpty()) 0f else base.arcUntil,
            chapters = recent,
            tm = phrases.values.toList(),
        )
    }

    private fun mergeCharacters(current: Map<String, Character>, found: List<JsonObject>): Map<String, Character> {
        val out = current.toMutableMap()
        found.forEach { c ->
            val name = c.str("name")
            val key = name.lowercase()
            if (key.isEmpty()) return@forEach
            val addresses = c.array("addresses").map { Address(it.str("to"), it.str("form")) }
            val existing = out[key]
            out[key] = if (existing == null) {
                Character(
                    name = name,
                    gender = c.str("gender").ifEmpty { null },
                    role = c.str("role").ifEmpty { null },
                    speech = c.str("speech").ifEmpty { null },
                    addresses = addresses,
                )
            } else if (existing.locked) {
                existing
            } else {
                val known = existing.addresses.map { it.to.lowercase() }.toSet()
                existing.copy(
                    gender = existing.gender?.takeUnless { it == "unknown" } ?: c.str("gender").ifEmpty { null },
                    role = existing.role ?: c.str("role").ifEmpty { null },
                    speech = existing.speech ?: c.str("speech").ifEmpty { null },
                    addresses = existing.addresses + addresses.filter { it.to.lowercase() !in known },
                )
            }
        }
        return out
    }

    /** Tries the stronger models first: a recap is a handful of requests worth doing well. */
    private suspend fun generate(
        system: String,
        user: String,
        schema: kotlinx.serialization.json.JsonElement,
    ): JsonObject {
        var last: LlmException? = null
        for (model in models) {
            try {
                return client.generate(
                    model,
                    system,
                    user,
                    schema,
                    temperature = 0.3,
                    attempts = 1,
                    timeoutSeconds = 300,
                ).json
            } catch (e: LlmException) {
                if (!e.isTemporary) throw e
                last = e
            }
        }
        throw last ?: LlmException(0, "No model available")
    }

    private companion object {
        const val CHAPTERS_PER_REQUEST = 10

        val ALIGN_SYSTEM = """
            You prepare the memory of an Italian translation of a Korean webtoon. You get the same chapters in the
            English scanlation and in the Italian scanlation the reader already read. Future English chapters will
            be translated into Italian and must match the Italian scanlation exactly.
            - glossary: every proper noun and recurring term (names, families, places, titles, ranks, honorifics,
              skills, organizations, creatures, items) mapped from the English form to EXACTLY the Italian form used
              (keep=true when the Italian keeps the original form). Spell names as the Italian scanlation does.
            - characters: each named character with gender, role, a few words on speech style in Italian, and the
              tu/lei/voi form the Italian scanlation uses from that character towards each other character.
            - phrases: up to 15 short recurring phrases (max 8 words) English -> Italian as used.
            OCR text can contain errors.
        """.trimIndent()

        val STORY_SYSTEM = """
            You read chapters of a webtoon in its Italian scanlation (OCR text, may contain errors) to remember the
            story for a translator. For each chapter write 2-4 Italian sentences on what happens (events,
            revelations, who is where). List the named characters with gender, role, speech style and the
            tu/lei/voi forms they use towards each other.
        """.trimIndent()

        val ARC_SYSTEM = """
            Merge the story so far and these chapter summaries into one Italian summary of at most 250 words,
            keeping what matters for later chapters: main plot, relationships, unresolved threads.
        """.trimIndent()

        private val CHARACTERS = """
            "characters":{"type":"array","items":{"type":"object","properties":{
              "name":{"type":"string"},"gender":{"type":"string","enum":["m","f","unknown"]},
              "role":{"type":"string"},"speech":{"type":"string"},
              "addresses":{"type":"array","items":{"type":"object","properties":{
                "to":{"type":"string"},"form":{"type":"string","enum":["tu","lei","voi"]}},"required":["to","form"]}}},
              "required":["name"]}}
        """.trimIndent()

        val ALIGN_SCHEMA = GeminiClient.json.parseToJsonElement(
            """
            {"type":"object","properties":{
              "glossary":{"type":"array","items":{"type":"object","properties":{
                "en":{"type":"string"},"it":{"type":"string"},
                "kind":{"type":"string","enum":["person","place","organization","title","skill","item","creature","term"]},
                "keep":{"type":"boolean"}},"required":["en","it","kind","keep"]}},
              $CHARACTERS,
              "phrases":{"type":"array","items":{"type":"object","properties":{
                "en":{"type":"string"},"it":{"type":"string"}},"required":["en","it"]}}},
              "required":["glossary","characters","phrases"]}
            """.trimIndent(),
        )

        val STORY_SCHEMA = GeminiClient.json.parseToJsonElement(
            """
            {"type":"object","properties":{
              "summaries":{"type":"array","items":{"type":"object","properties":{
                "n":{"type":"number"},"summary":{"type":"string","description":"In Italian"}},"required":["n","summary"]}},
              $CHARACTERS},
              "required":["summaries","characters"]}
            """.trimIndent(),
        )

        val ARC_SCHEMA = GeminiClient.json.parseToJsonElement(
            """{"type":"object","properties":{"arc_summary":{"type":"string"}},"required":["arc_summary"]}""",
        )

        fun JsonObject.str(name: String) = this[name]?.jsonPrimitive?.contentOrNull.orEmpty()

        fun JsonObject.array(name: String) = this[name]?.jsonArray?.map { it.jsonObject }.orEmpty()
    }
}
