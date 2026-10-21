package mihon.translation.context

import kotlinx.serialization.Serializable

/**
 * Per-series translation memory sent with every chapter. It grows with each chapter, but every section has a
 * hard token budget enforced here rather than trusted to the model.
 */
@Serializable
data class SeriesContext(
    val series: String,
    val version: Int = 0,
    val glossary: List<GlossaryEntry> = emptyList(),
    val characters: List<Character> = emptyList(),
    val arcSummary: String = "",
    val arcUntil: Float? = null,
    val chapters: List<ChapterSummary> = emptyList(),
    val tm: List<PhraseEntry> = emptyList(),
    val tail: Tail? = null,
)

@Serializable
data class GlossaryEntry(
    val en: String,
    val it: String,
    val kind: String = "term",
    val keep: Boolean = false,
    val note: String? = null,
    val locked: Boolean = false,
    val firstSeen: Float = 0f,
    val lastSeen: Float = 0f,
    val hits: Int = 0,
) {
    val target get() = if (keep) en else it
}

@Serializable
data class Address(val to: String, val form: String)

@Serializable
data class Character(
    val name: String,
    val gender: String? = null,
    val role: String? = null,
    val speech: String? = null,
    val addresses: List<Address> = emptyList(),
    val locked: Boolean = false,
    val firstSeen: Float = 0f,
    val lastSeen: Float = 0f,
    val hits: Int = 0,
)

@Serializable
data class ChapterSummary(val n: Float, val summary: String)

@Serializable
data class PhraseEntry(
    val en: String,
    val it: String,
    val firstSeen: Float = 0f,
    val lastSeen: Float = 0f,
    val hits: Int = 0,
)

@Serializable
data class TailLine(val en: String, val it: String)

@Serializable
data class Tail(val n: Float, val lines: List<TailLine>)
