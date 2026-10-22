package mihon.fork.translation

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.util.system.buildNotificationChannel
import eu.kanade.tachiyomi.util.system.notificationBuilder
import eu.kanade.tachiyomi.util.system.setForegroundSafely
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import mihon.core.metro.metroGraph
import mihon.translation.ApiKeyStore
import mihon.translation.ChapterTranslator
import mihon.translation.TranslationPipeline
import mihon.translation.context.SeriesContext
import mihon.translation.llm.GeminiClient
import mihon.translation.llm.LlmException
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.R
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Works through the translation queue in the background, one chapter at a time, in reading order. */
class TranslationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    @Inject lateinit var manager: TranslationManager

    @Inject lateinit var storage: TranslationStorage

    @Inject lateinit var preferences: TranslationPreferences

    @Inject lateinit var getManga: GetManga

    @Inject lateinit var getChapter: GetChapter

    @Inject lateinit var sourceManager: SourceManager

    @Inject lateinit var downloadManager: DownloadManager

    @Inject lateinit var downloadProvider: DownloadProvider

    @Inject lateinit var getChaptersByMangaId: GetChaptersByMangaId

    private val notifications = NotificationManagerCompat.from(context)

    init {
        context.metroGraph<TranslationGraph>().inject(this)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = ForegroundInfo(
        ID_PROGRESS,
        progress(applicationContext.getString(R.string.translation_notification_title), null).build(),
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    override suspend fun doWork(): Result {
        ensureChannel(applicationContext)
        setForegroundSafely()
        val pipeline = TranslationPipeline(
            context = applicationContext,
            apiKey = { manager.keys.get(ApiKeyStore.GEMINI) },
            onCall = { _, _ -> manager.recordRequest() },
            reviewModels = {
                if (preferences.qualityReview.get()) ChapterTranslator.QUALITY_REVIEW else ChapterTranslator.FAST_REVIEW
            },
        )
        var done = 0
        while (!isStopped) {
            val pausedUntil = preferences.pausedUntil.get()
            if (pausedUntil > System.currentTimeMillis()) {
                notify(ID_RESULT, applicationContext.getString(R.string.translation_quota_paused))
                start(applicationContext, pausedUntil - System.currentTimeMillis())
                return Result.success()
            }
            val next = manager.next() ?: break
            if (!manager.hasKey()) {
                notify(ID_RESULT, applicationContext.getString(R.string.translation_missing_key))
                return Result.failure()
            }
            if (next.attempts >= MAX_ATTEMPTS) {
                fail(next, "too many attempts")
                continue
            }
            val job = manager.beginAttempt(next)
            try {
                if (job.recap != null) recap(job, job.recap) else translate(job, pipeline)
                manager.remove(job)
                if (job.recap == null) done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                logcat(LogPriority.WARN, TAG) { "Chapter ${job.number}: ${e.status} ${e.message}" }
                when {
                    e.status == 429 && e.message.orEmpty().contains("PerDay", ignoreCase = true) -> {
                        // Not the chapter's fault: the attempt is given back
                        manager.refundAttempt(job)
                        preferences.pausedUntil.set(manager.nextQuotaReset())
                    }
                    e.isTemporary && job.attempts < MAX_ATTEMPTS -> {
                        delay(if (e.status == 429) 60.seconds else 30.seconds)
                    }
                    else -> fail(job, e.message ?: "HTTP ${e.status}")
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, TAG) { e.asLog() }
                if (job.attempts < MAX_ATTEMPTS) {
                    delay(30.seconds)
                } else {
                    fail(job, e.message ?: e::class.simpleName)
                }
            }
        }
        if (done > 0) notify(ID_RESULT, applicationContext.getString(R.string.translation_done, done))
        return Result.success()
    }

    private suspend fun recap(job: TranslationJob, request: RecapRequest) {
        val manga = getManga.await(job.mangaId) ?: error("Manga not found")
        val client = GeminiClient({ manager.keys.get(ApiKeyStore.GEMINI) }) { _, _ -> manager.recordRequest() }
        RecapRunner(
            applicationContext,
            sourceManager,
            getManga,
            getChaptersByMangaId,
            downloadProvider,
            storage,
            client,
        )
            .run(manga, request) { step ->
                updateTitle(applicationContext.getString(R.string.translation_recap_progress, manga.title), step)
            }
        notify(ID_RESULT, applicationContext.getString(R.string.translation_recap_done, manga.title))
    }

    private suspend fun translate(job: TranslationJob, pipeline: TranslationPipeline) {
        val manga = getManga.await(job.mangaId) ?: error("Manga not found")
        val chapter = getChapter.await(job.chapterId) ?: error("Chapter not found")
        val label = TranslationStorage.label(chapter.chapterNumber)
        update(manga, label, null)

        val dir = downloaded(manga, chapter)
        val work = File(applicationContext.cacheDir, "translation-work/${chapter.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val input = copyInput(dir, work)
            val output = File(work, "out.cbz")
            val ctx = storage.readContext(manga) ?: SeriesContext(series = manga.title)
            val (updated, timings) = pipeline.run(
                key = "${manga.id}:${chapter.id}",
                input = input,
                output = output,
                ctx = ctx,
                chapter = chapter.chapterNumber.toFloat(),
            ) { step -> update(manga, label, step) }
            notifyQualityFallback(timings.translation.reviewModel)
            storage.saveChapter(manga, chapter, output)
            storage.writeContext(manga, keepUserEdits(updated, ctx, storage.readContext(manga)))
        } finally {
            work.deleteRecursively()
        }
        if (preferences.deleteOriginal.get()) {
            downloadManager.deleteChapters(listOf(chapter), manga, sourceManager.getOrStub(manga.source))
        }
    }

    /** The downloaded chapter, downloading it first when needed. */
    private suspend fun downloaded(manga: Manga, chapter: Chapter): UniFile {
        val source = sourceManager.getOrStub(manga.source)
        suspend fun find() = downloadProvider.findChapterDir(
            chapter.name,
            chapter.scanlator,
            chapter.url,
            manga.title,
            source,
        )
        find()?.let { return it }
        downloadManager.downloadChapters(manga, listOf(chapter))
        val deadline = System.currentTimeMillis() + 15.minutes.inWholeMilliseconds
        while (System.currentTimeMillis() < deadline) {
            delay(3.seconds)
            val download = downloadManager.queueState.value.firstOrNull { it.chapter.id == chapter.id }
            if (download == null || download.status == Download.State.DOWNLOADED) find()?.let { return it }
            if (download?.status == Download.State.ERROR) error("Download failed")
        }
        error("Download timed out")
    }

    /** Copies the chapter out of the storage provider so the pipeline can read it as plain files. */
    private fun copyInput(dir: UniFile, work: File): File {
        if (!dir.isDirectory) {
            val cbz = File(work, "in.cbz")
            dir.openInputStream().use { input -> cbz.outputStream().use { input.copyTo(it) } }
            return cbz
        }
        val folder = File(work, "in").apply { mkdirs() }
        dir.listFiles()?.filter { it.isFile }?.forEach { file ->
            File(folder, file.name ?: return@forEach).outputStream().use { out ->
                file.openInputStream().use { it.copyTo(out) }
            }
        }
        return folder
    }

    /**
     * The context may have been edited while the chapter was being translated: locked entries and a changed
     * story summary from the latest file win over the copy the chapter started from.
     */
    private fun keepUserEdits(updated: SeriesContext, start: SeriesContext, latest: SeriesContext?): SeriesContext {
        if (latest == null || latest.version == start.version) return updated
        val lockedTerms = latest.glossary.filter { it.locked }.associateBy { it.en.lowercase() }
        val lockedCharacters = latest.characters.filter { it.locked }.associateBy { it.name.lowercase() }
        return updated.copy(
            glossary = updated.glossary.filterNot { it.en.lowercase() in lockedTerms } + lockedTerms.values,
            characters =
            updated.characters.filterNot { it.name.lowercase() in lockedCharacters } + lockedCharacters.values,
            arcSummary = if (latest.arcSummary != start.arcSummary) latest.arcSummary else updated.arcSummary,
            version = maxOf(updated.version, latest.version) + 1,
        )
    }

    /** Tells once a day that the full Flash reviewers are out of quota and the fast one stands in. */
    private fun notifyQualityFallback(reviewModel: String?) {
        if (!preferences.qualityReview.get() || reviewModel == null || reviewModel in QUALITY_ONLY) return
        val today = java.time.LocalDate.now(TranslationManager.QUOTA_ZONE).toString()
        if (preferences.qualityFallbackNotifiedDay.get() == today) return
        preferences.qualityFallbackNotifiedDay.set(today)
        notify(ID_QUALITY, applicationContext.getString(R.string.translation_quality_fallback))
    }

    private fun fail(job: TranslationJob, reason: String?) {
        manager.remove(job)
        notify(
            ID_RESULT,
            applicationContext.getString(R.string.translation_failed, "${job.title} ${job.number}: $reason"),
        )
    }

    private fun update(manga: Manga, chapter: String, step: String?) {
        updateTitle(
            applicationContext.getString(R.string.translation_notification_progress, manga.title, chapter),
            step,
        )
    }

    /** Updates the foreground notification in place, so it can be called from progress callbacks. */
    private fun updateTitle(title: String, step: String?) {
        val left = manager.queue.value.size
        val text = listOfNotNull(step, applicationContext.getString(R.string.translation_notification_queue, left))
        if (notifications.areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            notifications.notify(ID_PROGRESS, progress(title, text.joinToString(" · ")).build())
        }
    }

    private fun progress(title: String, text: String?) = applicationContext.notificationBuilder(CHANNEL) {
        setContentTitle(title)
        setContentText(text)
        setSmallIcon(android.R.drawable.ic_popup_sync)
        setOngoing(true)
        setOnlyAlertOnce(true)
        setPriority(NotificationCompat.PRIORITY_LOW)
    }

    private fun notify(id: Int, text: String) {
        val notification = applicationContext.notificationBuilder(CHANNEL) {
            setContentTitle(applicationContext.getString(R.string.translation))
            setContentText(text)
            setStyle(NotificationCompat.BigTextStyle().bigText(text))
            setSmallIcon(android.R.drawable.ic_popup_sync)
            setAutoCancel(true)
        }.build()
        if (notifications.areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            notifications.notify(id, notification)
        }
    }

    companion object {
        private const val TAG = "MihonTranslation"
        private const val WORK_NAME = "translation"
        private const val CHANNEL = "translation_channel"
        private const val ID_PROGRESS = -801
        private const val ID_RESULT = -802
        private const val ID_QUALITY = -803
        private val QUALITY_ONLY = ChapterTranslator.QUALITY_REVIEW - ChapterTranslator.FAST_REVIEW.toSet()
        private const val MAX_ATTEMPTS = 3

        /**
         * Starts the queue unless a worker is already running it. A worker left waiting by a crash or a backoff
         * is replaced so the queue resumes right away. Blocking: call it off the main thread.
         */
        fun start(context: Context, delayMillis: Long = 0) {
            val workManager = WorkManager.getInstance(context)
            val running = workManager.getWorkInfosForUniqueWork(WORK_NAME).get().any {
                it.state ==
                    WorkInfo.State.RUNNING
            }
            if (running && delayMillis == 0L) return
            val request = OneTimeWorkRequestBuilder<TranslationWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            workManager.enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        fun ensureChannel(context: Context) {
            NotificationManagerCompat.from(context).createNotificationChannel(
                buildNotificationChannel(CHANNEL, NotificationManagerCompat.IMPORTANCE_LOW) {
                    setName(context.getString(R.string.translation))
                },
            )
        }
    }
}
