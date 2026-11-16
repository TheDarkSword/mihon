package mihon.translation.pc

import kotlinx.serialization.Serializable
import mihon.translation.context.ChapterText
import mihon.translation.context.SeriesContext

/** Shapes exchanged with the translator running on the reader's PC (mihon-it-translator, API v1). */

@Serializable
data class PcSeries(val key: String, val title: String)

@Serializable
data class PcLineSpec(val id: Int, val region: String, val text: String, val max: Int)

@Serializable
data class PcImageSpec(val file: String, val regions: List<Int>)

@Serializable
data class PcJobSpec(
    val type: String = "translate",
    val clientKey: String,
    val model: String,
    val series: PcSeries,
    val chapter: Float,
    val lines: List<PcLineSpec>,
    val images: List<PcImageSpec>,
    /** The series memory on the phone and the last copy received from the PC, to merge the reader's edits. */
    val context: SeriesContext? = null,
    val contextBase: SeriesContext? = null,
)

/** Chapters the reader already read, as read by the phone, for the PC to build the series memory. */
@Serializable
data class PcRecapSpec(
    val type: String = "recap",
    val clientKey: String,
    val model: String,
    val series: PcSeries,
    /** The reader read up to this chapter. */
    val chapter: Float,
    val aligned: List<PcAligned>,
    val italian: List<ChapterText>,
    val images: List<PcImageSpec> = emptyList(),
    val context: SeriesContext? = null,
    val contextBase: SeriesContext? = null,
)

@Serializable
data class PcAligned(val en: ChapterText, val it: ChapterText)

/** Asks the PC to clean the series memory: duplicates, inconsistent forms, an outdated story so far. */
@Serializable
data class PcReviewSpec(
    val type: String = "review",
    val clientKey: String,
    val model: String,
    val series: PcSeries,
    val chapter: Float = 0f,
    val images: List<PcImageSpec> = emptyList(),
    val context: SeriesContext? = null,
    val contextBase: SeriesContext? = null,
)

@Serializable
data class PcJob(
    val id: String,
    val type: String = "translate",
    val clientKey: String = "",
    val seriesKey: String = "",
    val seriesTitle: String = "",
    val chapter: Float = 0f,
    val model: String = "",
    val state: String,
    val error: String? = null,
    val revision: Int = 0,
    val position: Int? = null,
) {
    val isDone get() = state == "done"
    val isOver get() = state in setOf("done", "failed", "cancelled")
}

@Serializable
data class PcWindow(val utilization: Double? = null, val resetsAt: Double? = null)

@Serializable
data class PcStatus(
    val version: String = "",
    val hold: Boolean = false,
    /** Epoch seconds. */
    val pausedUntil: Double? = null,
    val pauseReason: String? = null,
    val limits: Map<String, PcWindow>? = null,
    val running: PcJob? = null,
    val queue: List<PcJob> = emptyList(),
) {
    val fiveHour get() = limits?.get("five_hour")?.utilization
    val week get() = limits?.get("seven_day")?.utilization
}

@Serializable
data class PcLine(val id: Int, val en: String, val it: String, val type: String, val speaker: String = "")

/** A line where the English scanlation contradicted the series memory and the translation followed the memory. */
@Serializable
data class PcCorrection(
    val chapter: Float,
    val id: Int,
    val en: String = "",
    val it: String = "",
    val reason: String = "",
)

@Serializable
data class PcResult(
    val id: String,
    val seriesKey: String,
    val chapter: Float,
    val revision: Int = 0,
    val model: String = "",
    val lines: List<PcLine>,
    val corrections: List<PcCorrection> = emptyList(),
    val context: SeriesContext? = null,
    /** What a memory review changed, in Italian. */
    val notes: String = "",
)

/** What the PC answers to a phone waiting for news: everything as it is right after the change. */
@Serializable
data class PcEvents(val cursor: Long, val status: PcStatus, val jobs: List<PcJob>)

@Serializable
data class PcInfo(val name: String, val version: String, val paired: Boolean = false)

@Serializable
data class PcContextSync(val context: SeriesContext? = null, val corrections: List<PcCorrection> = emptyList())

@Serializable
internal data class PcJobs(val jobs: List<PcJob>)

@Serializable
internal data class PcToken(val token: String)

@Serializable
internal data class PcError(val error: String = "")

@Serializable
internal data class PcPairRequest(val device: String, val code: String? = null)

@Serializable
internal data class PcSyncRequest(val context: SeriesContext, val base: SeriesContext?)
