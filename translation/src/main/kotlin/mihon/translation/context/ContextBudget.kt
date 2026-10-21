package mihon.translation.context

/** The slice of a [SeriesContext] visible while translating one chapter, within the token budgets. */
data class ContextView(
    val glossary: List<GlossaryEntry>,
    val characters: List<Character>,
    val arcSummary: String,
    val chapters: List<ChapterSummary>,
    val tm: List<PhraseEntry>,
    val tail: Tail?,
)

object ContextBudget {
    const val GLOSSARY = 2500
    const val CHARACTERS = 1500
    const val STORY = 2500
    const val TM = 1000
    const val TAIL_LINES = 15
    const val RECENT_SUMMARIES = 5

    fun tokens(text: String): Int = text.length / 4 + 1

    /** Nothing learned from later chapters is visible, so translating out of order never leaks future facts. */
    fun forChapter(ctx: SeriesContext, chapter: Float): ContextView = ContextView(
        glossary = fit(ctx.glossary.filter { it.firstSeen < chapter }, GLOSSARY, ::render, { it.locked }) {
            Triple(it.locked, it.lastSeen, it.hits)
        },
        characters = fit(ctx.characters.filter { it.firstSeen < chapter }, CHARACTERS, ::render, { it.locked }) {
            Triple(it.locked, it.lastSeen, it.hits)
        },
        arcSummary = if ((ctx.arcUntil ?: 0f) < chapter) ctx.arcSummary else "",
        chapters = ctx.chapters.filter { it.n < chapter }.takeLast(RECENT_SUMMARIES),
        tm = fit(ctx.tm.filter { it.firstSeen < chapter }, TM, ::render, { false }) {
            Triple(false, it.lastSeen, it.hits)
        },
        tail = ctx.tail?.takeIf { it.n < chapter },
    )

    private fun <T> fit(
        entries: List<T>,
        budget: Int,
        render: (T) -> String,
        locked: (T) -> Boolean,
        rank: (T) -> Triple<Boolean, Float, Int>,
    ): List<T> {
        val sorted = entries.sortedWith(
            compareByDescending<T> { rank(it).first }
                .thenByDescending { rank(it).second }
                .thenByDescending { rank(it).third },
        )
        var used = 0
        return sorted.filter {
            val cost = tokens(render(it))
            (used + cost <= budget || locked(it)).also { fits -> if (fits) used += cost }
        }
    }

    fun render(e: GlossaryEntry): String {
        val note = e.note?.takeIf { it.isNotBlank() }?.let { "; $it" }.orEmpty()
        return "- ${e.en} => ${e.target} (${e.kind}${if (e.keep) ", keep" else ""}$note)"
    }

    fun render(c: Character): String {
        val parts = listOfNotNull(c.name, c.gender ?: "?", c.role, c.speech).filter { it.isNotBlank() }
        val forms = c.addresses.joinToString(", ") { "${it.form} to ${it.to}" }
        return "- " + parts.joinToString(" | ") + if (forms.isNotEmpty()) " | addresses: $forms" else ""
    }

    fun render(e: PhraseEntry): String = "- ${e.en} => ${e.it}"

    fun render(view: ContextView): String = buildString {
        fun section(title: String, lines: List<String>) {
            if (lines.isEmpty()) return
            appendLine("## $title")
            lines.forEach { appendLine(it) }
            appendLine()
        }
        section("Glossary (mandatory)", view.glossary.map(::render))
        section("Characters", view.characters.map(::render))
        if (view.arcSummary.isNotBlank()) section("Story so far", listOf(view.arcSummary))
        section("Recent chapters", view.chapters.map { "- Ch. ${it.n.label()}: ${it.summary}" })
        section("Recurring phrases already used", view.tm.map(::render))
        view.tail?.let { tail ->
            section("End of chapter ${tail.n.label()}", tail.lines.map { "- ${it.en} => ${it.it}" })
        }
    }

    /** Oldest summaries that must be folded into the arc summary to respect the story budget. */
    fun storyOverflow(ctx: SeriesContext): List<ChapterSummary> {
        var used = tokens(ctx.arcSummary) + ctx.chapters.sumOf { tokens(it.summary) }
        val over = mutableListOf<ChapterSummary>()
        var i = 0
        while ((used > STORY || ctx.chapters.size - i > RECENT_SUMMARIES) && i < ctx.chapters.size - 1) {
            used -= tokens(ctx.chapters[i].summary)
            over += ctx.chapters[i]
            i++
        }
        return over
    }
}

fun Float.label(): String = if (this % 1f == 0f) toInt().toString() else toString()
