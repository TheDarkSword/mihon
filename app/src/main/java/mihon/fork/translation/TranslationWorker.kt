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
import mihon.translation.TranslationPipeline
import mihon.translation.context.SeriesContext
import mihon.translation.llm.LlmException
import tachiyomi.domain.chapter.interactor.GetChapter
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
        val pipeline = TranslationPipeline(applicationContext, { manager.keys.get(ApiKeyStore.GEMINI) }) { _, _ ->
            manager.recordRequest()
        }
        var done = 0
        while (!isStopped) {
            val pausedUntil = preferences.pausedUntil.get()
            if (pausedUntil > System.currentTimeMillis()) {
                notify(ID_RESULT, applicationContext.getString(R.string.translation_quota_paused))
                start(applicationContext, pausedUntil - System.currentTimeMillis())
                return Result.success()
            }
            val job = manager.next() ?: break
            if (!manager.hasKey()) {
                notify(ID_RESULT, applicationContext.getString(R.string.translation_missing_key))
                return Result.failure()
            }
            try {
                translate(job, pipeline)
                manager.remove(job)
                done++
            } catch (e: CancellationException) {
                throw e
            } catch (e: LlmException) {
                logcat(LogPriority.WARN, TAG) { "Chapter ${job.number}: ${e.status} ${e.message}" }
                when {
                    e.status == 429 && e.message.orEmpty().contains("PerDay", ignoreCase = true) ->
                        preferences.pausedUntil.set(manager.nextQuotaReset())
                    e.isTemporary && job.attempts < MAX_ATTEMPTS -> {
                        manager.retryLater(job)
                        delay(if (e.status == 429) 60.seconds else 30.seconds)
                    }
                    else -> fail(job, e.message ?: "HTTP ${e.status}")
                }
            } catch (e: Exception) {
                logcat(LogPriority.ERROR, TAG) { e.asLog() }
                if (job.attempts < MAX_ATTEMPTS) {
                    manager.retryLater(job)
                    delay(30.seconds)
                } else {
                    fail(job, e.message ?: e::class.simpleName)
                }
            }
        }
        if (done > 0) notify(ID_RESULT, applicationContext.getString(R.string.translation_done, done))
        return Result.success()
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
            val (updated, _) = pipeline.run(
                key = "${manga.id}:${chapter.id}",
                input = input,
                output = output,
                ctx = ctx,
                chapter = chapter.chapterNumber.toFloat(),
            ) { step -> update(manga, label, step) }
            storage.saveChapter(manga, chapter, output)
            storage.writeContext(manga, updated)
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

    private fun fail(job: TranslationJob, reason: String?) {
        manager.remove(job)
        notify(
            ID_RESULT,
            applicationContext.getString(R.string.translation_failed, "${job.title} ${job.number}: $reason"),
        )
    }

    /** Updates the foreground notification in place, so it can be called from the pipeline's progress callback. */
    private fun update(manga: Manga, chapter: String, step: String?) {
        val title = applicationContext.getString(R.string.translation_notification_progress, manga.title, chapter)
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
        private const val MAX_ATTEMPTS = 3

        fun start(context: Context, delayMillis: Long = 0) {
            val request = OneTimeWorkRequestBuilder<TranslationWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .build()
            val policy = if (delayMillis > 0) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, policy, request)
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
