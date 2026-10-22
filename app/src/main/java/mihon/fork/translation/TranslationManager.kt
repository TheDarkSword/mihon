package mihon.fork.translation

import android.content.Context
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import mihon.translation.ApiKeyStore
import mihon.translation.TranslationPipeline
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

@Serializable
data class TranslationJob(
    val mangaId: Long,
    val chapterId: Long,
    val number: Double,
    val title: String,
    val attempts: Int = 0,
    val recap: RecapRequest? = null,
)

/** Build the series context from the Italian scanlation the reader already read, up to chapter [until]. */
@Serializable
data class RecapRequest(
    val sourceId: Long,
    val mangaUrl: String,
    val title: String,
    val until: Double,
    val precision: Int,
    /** The Italian series as saved in the library, so downloaded chapters can be read without the network. */
    val localMangaId: Long? = null,
)

/** Persistent translation queue and the lookups the reader and the manga screen need. */
@Inject
@SingleIn(AppScope::class)
class TranslationManager(
    private val context: Context,
    private val storage: TranslationStorage,
    private val preferences: TranslationPreferences,
) {

    private val file = File(context.filesDir, "translation_queue.json")
    private val _queue = MutableStateFlow(load())
    val queue: StateFlow<List<TranslationJob>> = _queue.asStateFlow()

    val keys by lazy { ApiKeyStore(context) }

    fun hasKey() = !keys.get(ApiKeyStore.GEMINI).isNullOrBlank()

    data class Enqueued(val added: Int, val alreadyQueued: Int)

    /** Adds the chapters not translated nor queued yet, in reading order, and makes sure the worker runs. */
    fun enqueue(manga: Manga, chapters: List<Chapter>): Enqueued {
        val queued = _queue.value.map { it.chapterId }.toSet()
        val candidates = chapters.filter { it.chapterNumber >= 0 && !storage.isTranslated(manga, it) }
        val jobs = candidates
            .filter { it.id !in queued }
            .sortedBy { it.chapterNumber }
            .map { TranslationJob(manga.id, it.id, it.chapterNumber, manga.title) }
        if (jobs.isNotEmpty()) {
            _queue.update { inReadingOrder(it + jobs) }
            save()
        }
        if (_queue.value.isNotEmpty()) TranslationWorker.start(context)
        return Enqueued(jobs.size, candidates.size - jobs.size)
    }

    /** Resumes a queue left behind, e.g. after the app was killed. Blocking. */
    fun resumeIfNeeded() {
        if (_queue.value.isNotEmpty() && hasKey()) TranslationWorker.start(context)
    }

    /** Queues a recap ahead of the series' chapters, replacing a recap still waiting for the same series. */
    fun enqueueRecap(manga: Manga, request: RecapRequest) {
        val job = TranslationJob(manga.id, recapId(manga), RECAP_NUMBER, manga.title, recap = request)
        _queue.update { queue -> inReadingOrder(queue.filterNot { it.chapterId == job.chapterId } + job) }
        save()
        TranslationWorker.start(context)
    }

    fun next(): TranslationJob? = _queue.value.firstOrNull()

    fun remove(job: TranslationJob) {
        _queue.update { queue -> queue.filterNot { it.chapterId == job.chapterId } }
        save()
    }

    /**
     * Counts the attempt before working on the job: if the process dies halfway (e.g. a native crash) the next
     * run still sees it, so a chapter that keeps crashing is eventually dropped instead of looping.
     */
    fun beginAttempt(job: TranslationJob): TranslationJob {
        val started = job.copy(attempts = job.attempts + 1)
        _queue.update { queue -> queue.map { if (it.chapterId == job.chapterId) started else it } }
        save()
        return started
    }

    fun refundAttempt(job: TranslationJob) {
        _queue.update { queue ->
            queue.map {
                if (it.chapterId ==
                    job.chapterId
                ) {
                    it.copy(attempts = (it.attempts - 1).coerceAtLeast(0))
                } else {
                    it
                }
            }
        }
        save()
    }

    fun clear() {
        _queue.value = emptyList()
        save()
    }

    /** The translated chapter to open in the reader, unless the series is set to show originals. */
    fun translatedArchive(manga: Manga, chapter: Chapter): UniFile? {
        if (manga.id.toString() in preferences.originalManga.get()) return null
        return storage.chapterFile(manga, chapter)
    }

    fun showsTranslation(manga: Manga) = manga.id.toString() !in preferences.originalManga.get()

    fun setShowTranslation(manga: Manga, show: Boolean) {
        val ids = preferences.originalManga.get()
        preferences.originalManga.set(if (show) ids - manga.id.toString() else ids + manga.id.toString())
    }

    /** Gemini free quotas reset at midnight Pacific time. */
    fun recordRequest() {
        val today = LocalDate.now(QUOTA_ZONE).toString()
        if (preferences.requestsDay.get() != today) {
            preferences.requestsDay.set(today)
            preferences.requestsCount.set(0)
        }
        preferences.requestsCount.set(preferences.requestsCount.get() + 1)
    }

    fun requestsToday(): Int =
        if (preferences.requestsDay.get() ==
            LocalDate.now(QUOTA_ZONE).toString()
        ) {
            preferences.requestsCount.get()
        } else {
            0
        }

    fun nextQuotaReset(): Long = LocalDate.now(
        QUOTA_ZONE,
    ).plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant().toEpochMilli()

    /**
     * Series keep the order in which they were first queued, chapters of each series go by number: the context
     * of a series must grow chapter after chapter.
     */
    private fun inReadingOrder(jobs: List<TranslationJob>): List<TranslationJob> {
        val seriesOrder = jobs.map { it.mangaId }.distinct()
        return jobs.sortedWith(compareBy({ seriesOrder.indexOf(it.mangaId) }, { it.number }))
    }

    private fun load(): List<TranslationJob> = runCatching {
        TranslationPipeline.json.decodeFromString(ListSerializer(TranslationJob.serializer()), file.readText())
    }.getOrDefault(emptyList())

    private fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.writeText(
            TranslationPipeline.json.encodeToString(ListSerializer(TranslationJob.serializer()), _queue.value),
        )
        tmp.renameTo(file)
    }

    companion object {
        /** Recaps sort before every chapter of their series. */
        const val RECAP_NUMBER = -1.0

        fun recapId(manga: Manga) = -manga.id

        val QUOTA_ZONE: ZoneId = ZoneId.of("America/Los_Angeles")
    }
}
