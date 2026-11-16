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
import mihon.translation.ChapterTranslation
import mihon.translation.ChapterTranslator
import mihon.translation.TranslationPipeline
import mihon.translation.context.SeriesContext
import mihon.translation.llm.GeminiClient
import mihon.translation.llm.LlmException
import mihon.translation.model.TextBlock
import mihon.translation.model.TranslatedLine
import mihon.translation.pc.PcClient
import mihon.translation.pc.PcException
import mihon.translation.pc.PcJob
import mihon.translation.pc.PcJobSpec
import mihon.translation.pc.PcResult
import mihon.translation.pc.PcSeries
import mihon.translation.pc.PcStatus
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import tachiyomi.i18n.R
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream
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

    @Inject lateinit var pc: PcTranslator

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
        var handOver = 0L
        try {
            while (!isStopped) {
                done += collectFromPc(pipeline)
                val geminiPaused = preferences.pausedUntil.get() > System.currentTimeMillis()
                val next = nextActionable(geminiPaused)
                if (next == null) {
                    // Nothing to do on the phone: stay in the foreground while the PC works, so the results
                    // arrive with the screen off too, unless the PC is away or paused for long
                    if (!pc.isPaired() || manager.queue.value.none { it.waitingOnPc }) break
                    handOver = waitForPc()
                    if (handOver > 0) break
                    continue
                }
                if (next.attempts >= MAX_ATTEMPTS) {
                    fail(next, "too many attempts")
                    continue
                }
                val job = manager.beginAttempt(next)
                try {
                    when {
                        job.recap != null -> recap(job, job.recap)
                        job.render -> rerender(job, pipeline)
                        job.onPc && pc.isPaired() -> {
                            // The chapter stays queued until the PC sends the translation back
                            prepareForPc(job, pipeline)
                            continue
                        }
                        else -> translate(job, pipeline)
                    }
                    manager.remove(job)
                    if (job.recap == null && !job.render) done++
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
        } finally {
            // Also runs when the system stops the worker, e.g. at the foreground service time limit
            val queue = manager.queue.value
            if (queue.any { it.waitingOnPc }) {
                PcPollWorker.schedule(applicationContext, handOver.takeIf { it > 0 } ?: PcPollWorker.POLL_MILLIS)
                pcWaitingText?.let { PcPollWorker.show(applicationContext, it) }
            } else {
                PcPollWorker.dismiss(applicationContext)
            }
        }
        if (done > 0) notify(ID_RESULT, applicationContext.getString(R.string.translation_done, done))
        val queue = manager.queue.value
        val geminiLeft = queue.filter { !it.render && !(it.onPc && pc.isPaired()) }
        if (geminiLeft.isNotEmpty()) {
            val pausedUntil = preferences.pausedUntil.get()
            when {
                !manager.hasKey() -> notify(ID_RESULT, applicationContext.getString(R.string.translation_missing_key))
                pausedUntil > System.currentTimeMillis() -> {
                    notify(ID_RESULT, applicationContext.getString(R.string.translation_quota_paused))
                    start(applicationContext, pausedUntil - System.currentTimeMillis())
                }
            }
        }
        return Result.success()
    }

    /** Last state of the PC shown in the notification, kept there when the worker hands over. */
    private var pcWaitingText: String? = null
    private var unreachableSince = 0L
    private var pcCursor = -1L

    /**
     * Shows what the PC is doing and waits a little. Returns 0 to keep waiting in the foreground, or the delay
     * after which a background check should look again when waiting here makes no sense.
     */
    private suspend fun waitForPc(): Long {
        val waiting = manager.queue.value.filter { it.waitingOnPc }
        // First call answers at once (state to show), the next ones as soon as something changes on the PC
        val events = try {
            pc.withClient { it.waitForChange(pcCursor, if (pcCursor < 0) 0 else PcClient.WAIT_SECONDS) }
        } catch (e: PcException) {
            null
        }
        pcCursor = events?.cursor ?: -1
        val status = events?.status
        val now = System.currentTimeMillis()
        val title = applicationContext.getString(R.string.translation_pc_title)
        if (status == null) {
            if (unreachableSince == 0L) unreachableSince = now
            showPc(title, applicationContext.getString(R.string.translation_pc_unreachable, waiting.size))
            if (now - unreachableSince > UNREACHABLE_FOREGROUND_MILLIS) return UNREACHABLE_POLL_MILLIS
            delay(UNREACHABLE_RETRY_MILLIS)
        } else {
            unreachableSince = 0L
            val pausedUntil = status.pausedUntil?.let { (it * 1000).toLong() } ?: 0L
            if (pausedUntil > now) {
                val time = java.text.DateFormat.getTimeInstance(
                    java.text.DateFormat.SHORT,
                ).format(java.util.Date(pausedUntil))
                showPc(title, applicationContext.getString(R.string.translation_pc_paused, time, waiting.size))
                if (pausedUntil - now > LONG_PAUSE_MILLIS) return pausedUntil - now + 60_000
            } else {
                showPc(title, describe(status, waiting))
            }
        }
        return 0
    }

    private fun describe(status: PcStatus, waiting: List<TranslationJob>): String {
        val mine = waiting.mapNotNull { it.pcRemoteId }.toSet()
        val running = status.running?.takeIf { it.id in mine }
        val position = status.queue.filter { it.state == "queued" }.indexOfFirst { it.id in mine }
        val head = when {
            running != null -> applicationContext.getString(
                R.string.translation_pc_running,
                running.seriesTitle,
                TranslationStorage.label(running.chapter.toDouble()),
                running.model.replaceFirstChar { it.uppercase() },
            )
            position >= 0 -> applicationContext.getString(R.string.translation_pc_queued, position + 1)
            else -> applicationContext.getString(R.string.translation_step_send_pc)
        }
        return head + " · " + applicationContext.getString(R.string.translation_pc_waiting_count, waiting.size)
    }

    private fun showPc(title: String, text: String) {
        pcWaitingText = text
        if (notifications.areNotificationsEnabled()) {
            @Suppress("MissingPermission")
            notifications.notify(ID_PROGRESS, progress(title, text).build())
        }
    }

    /**
     * The first job the phone can work on now. Chapters already prepared for the PC wait for it, a series
     * waits for its recap, Gemini jobs wait for the key and the daily quota.
     */
    private fun nextActionable(geminiPaused: Boolean): TranslationJob? {
        val queue = manager.queue.value
        val paired = pc.isPaired()
        val gemini = !geminiPaused && manager.hasKey()
        return queue.firstOrNull { job ->
            when {
                job.recap != null -> gemini
                job.render -> true
                job.onPc && paired -> !job.pcPrepared && queue.none { it.mangaId == job.mangaId && it.recap != null }
                else -> gemini
            }
        }
    }

    /** Detection and OCR here, then the package goes to the PC, right away if it answers. */
    private suspend fun prepareForPc(job: TranslationJob, pipeline: TranslationPipeline) {
        val manga = getManga.await(job.mangaId) ?: error("Manga not found")
        val chapter = getChapter.await(job.chapterId) ?: error("Chapter not found")
        val label = TranslationStorage.label(chapter.chapterNumber)
        update(manga, label, applicationContext.getString(R.string.translation_step_download))
        val dir = downloaded(manga, chapter)
        val work = File(applicationContext.cacheDir, "translation-work/${chapter.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val input = copyInput(dir, work)
            val key = pc.seriesKey(manga)
            pc.rememberSeries(key, manga.id)
            val local = storage.readContext(manga) ?: SeriesContext(series = manga.title)
            val base = pc.base(key)
            pc.pendingDir(job.chapterId).apply {
                deleteRecursively()
                mkdirs()
            }
            val blocks = pipeline.prepareForPc(
                key = "${manga.id}:${chapter.id}",
                input = input,
                series = manga.title,
                zip = pc.packageFile(job.chapterId),
                progress = { step -> update(manga, label, step) },
            ) { lines, images ->
                PcJobSpec(
                    clientKey = "${job.chapterId}-${job.pcTries}",
                    model = job.model.ifBlank { preferences.pcModel.get() },
                    series = PcSeries(key, manga.title),
                    chapter = chapter.chapterNumber.toFloat(),
                    lines = lines,
                    images = images,
                    context = local,
                    contextBase = base,
                )
            }
            pc.saveBlocks(job.chapterId, blocks)
        } finally {
            work.deleteRecursively()
        }
        val prepared = job.copy(pcPrepared = true, attempts = 0)
        manager.replace(prepared)
        update(manga, label, applicationContext.getString(R.string.translation_step_send_pc))
        try {
            upload(prepared)
        } catch (e: PcException) {
            logcat(LogPriority.INFO, TAG) { "PC not reachable, chapter ${job.number} waits: ${e.message}" }
        }
    }

    /** Sends a prepared package. A package the PC refuses is a broken chapter; an unreachable PC throws. */
    private suspend fun upload(job: TranslationJob) {
        val zip = pc.packageFile(job.chapterId)
        if (!zip.exists()) return
        val remote = try {
            pc.withClient { it.upload(zip) }
        } catch (e: PcException) {
            if (e.status !in 400..499) throw e
            fail(job, e.message)
            pc.clearPending(job.chapterId)
            return
        }
        manager.replace(job.copy(pcRemoteId = remote.id))
        zip.delete()
    }

    /** Uploads what is waiting, then letters the translations the PC finished. Returns the chapters done. */
    private suspend fun collectFromPc(pipeline: TranslationPipeline): Int {
        if (!pc.isPaired()) return 0
        pc.clearPendingExcept(manager.queue.value.filter { it.onPc }.map { it.chapterId }.toSet())
        try {
            manager.queue.value.filter { it.waitingOnPc && it.pcRemoteId == null }.forEach { upload(it) }
            val remote = pc.withClient { it.jobs() }
            val local = manager.queue.value.filter { it.waitingOnPc && it.pcRemoteId != null }
            if (remote.none { it.isOver } && local.all { job -> remote.any { it.id == job.pcRemoteId } }) return 0
            var done = 0
            val lettered = mutableSetOf<Long>()
            for (job in local.sortedBy { it.number }) {
                val found = remote.firstOrNull { it.id == job.pcRemoteId }
                when {
                    // The PC lost it (cleaned or reinstalled): prepared again from the cached OCR
                    found == null -> manager.replace(
                        job.copy(pcPrepared = false, pcRemoteId = null, pcTries = job.pcTries + 1),
                    )
                    found.isDone -> if (letterFromPc(job, found, pipeline)) {
                        done++
                        lettered += job.mangaId
                    }
                    found.state == "failed" || found.state == "cancelled" -> {
                        runCatching { pc.withClient { it.ack(found.id) } }
                        pc.clearPending(job.chapterId)
                        fail(job, found.error ?: found.state)
                    }
                }
            }
            val known = local.mapNotNull { it.pcRemoteId }.toSet()
            for (found in remote.filter { it.isDone && it.id !in known }) {
                if (found.revision > 0) {
                    letterRevision(found, pipeline)?.let { mangaId ->
                        done++
                        lettered += mangaId
                    }
                } else if (manager.queue.value.none { it.pcRemoteId == found.id }) {
                    // Finished after the chapter left the queue (moved to Gemini or queue cleared)
                    runCatching { pc.withClient { it.ack(found.id) } }
                }
            }
            for (mangaId in lettered) {
                val manga = getManga.await(mangaId) ?: continue
                runCatching { pc.sync(manga) }.onFailure { logcat(LogPriority.WARN, TAG) { it.asLog() } }
            }
            return done
        } catch (e: PcException) {
            logcat(LogPriority.INFO, TAG) { "PC: ${e.message}" }
            return 0
        }
    }

    private suspend fun letterFromPc(job: TranslationJob, remote: PcJob, pipeline: TranslationPipeline): Boolean {
        val started = manager.beginAttempt(job)
        return try {
            val manga = getManga.await(job.mangaId) ?: error("Manga not found")
            val chapter = getChapter.await(job.chapterId) ?: error("Chapter not found")
            update(
                manga,
                TranslationStorage.label(chapter.chapterNumber),
                applicationContext.getString(R.string.translation_step_receive_pc),
            )
            val blocks = pc.blocks(job.chapterId) ?: error("Prepared data missing")
            val result = pc.withClient { it.result(remote.id) }
            letter(manga, chapter, blocks, result, pipeline)
            pipeline.dropCheckpoints("${manga.id}:${chapter.id}")
            runCatching { pc.withClient { it.ack(remote.id) } }
            pc.clearPending(job.chapterId)
            manager.remove(job)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: PcException) {
            manager.refundAttempt(started)
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, TAG) { e.asLog() }
            if (started.attempts >= MAX_ATTEMPTS) fail(job, e.message ?: e::class.simpleName)
            false
        }
    }

    /** A chapter translated again on the PC, at the reader's request: lettered over the same text regions. */
    private suspend fun letterRevision(remote: PcJob, pipeline: TranslationPipeline): Long? {
        val mangaId = pc.mangaFor(remote.seriesKey) ?: return null
        val manga = getManga.await(mangaId) ?: return null
        val chapter = getChaptersByMangaId.await(mangaId).firstOrNull {
            it.chapterNumber.toFloat() == remote.chapter
        } ?: return null
        val saved = storage.chapterFile(manga, chapter) ?: return null
        val data = readTranslation(saved) ?: return null
        update(
            manga,
            TranslationStorage.label(chapter.chapterNumber),
            applicationContext.getString(R.string.translation_step_receive_pc),
        )
        return try {
            letter(manga, chapter, data.blocks, pc.withClient { it.result(remote.id) }, pipeline)
            runCatching { pc.withClient { it.ack(remote.id) } }
            mangaId
        } catch (e: PcException) {
            throw e
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, TAG) { e.asLog() }
            null
        }
    }

    private suspend fun letter(
        manga: Manga,
        chapter: Chapter,
        blocks: List<TextBlock>,
        result: PcResult,
        pipeline: TranslationPipeline,
    ) {
        val dir = downloaded(manga, chapter)
        val work = File(applicationContext.cacheDir, "translation-work/${chapter.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val input = copyInput(dir, work)
            val output = File(work, "out.cbz")
            val data = ChapterTranslation(
                series = manga.title,
                chapter = chapter.chapterNumber.toFloat(),
                model = result.model.ifBlank { "pc" },
                blocks = blocks,
                lines = result.lines.map { TranslatedLine(it.id, it.en, it.it, it.type) },
            )
            update(
                manga,
                TranslationStorage.label(chapter.chapterNumber),
                applicationContext.getString(R.string.translation_step_typesetting),
            )
            pipeline.rerender(input, data, output, preferences.textStyle())
            storage.saveChapter(manga, chapter, output)
        } finally {
            work.deleteRecursively()
        }
        if (preferences.deleteOriginal.get()) {
            downloadManager.deleteChapters(listOf(chapter), manga, sourceManager.getOrStub(manga.source))
        }
    }

    private fun readTranslation(saved: UniFile): ChapterTranslation? = saved.openInputStream().use { stream ->
        ZipInputStream(stream.buffered()).use { zip ->
            generateSequence { zip.nextEntry }.firstOrNull { it.name == TranslationPipeline.JSON_ENTRY }
                ?.let {
                    TranslationPipeline.json.decodeFromString<ChapterTranslation>(zip.readBytes().decodeToString())
                }
        }
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
        update(manga, label, applicationContext.getString(R.string.translation_step_download))

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
                style = preferences.textStyle(),
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

    private suspend fun rerender(job: TranslationJob, pipeline: TranslationPipeline) {
        val manga = getManga.await(job.mangaId) ?: error("Manga not found")
        val chapter = getChapter.await(job.chapterId) ?: error("Chapter not found")
        val label = TranslationStorage.label(chapter.chapterNumber)
        val title = applicationContext.getString(R.string.translation_rerender_progress, manga.title, label)
        updateTitle(title, applicationContext.getString(R.string.translation_step_download))
        val saved = storage.chapterFile(manga, chapter) ?: error("Translation not found")
        val data = readTranslation(saved) ?: error("No translation data in the chapter")
        val dir = downloaded(manga, chapter)
        val work = File(applicationContext.cacheDir, "translation-work/${chapter.id}").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val output = File(work, "out.cbz")
            val input = copyInput(dir, work)
            updateTitle(title, applicationContext.getString(R.string.translation_step_typesetting))
            pipeline.rerender(input, data, output, preferences.textStyle())
            storage.saveChapter(manga, chapter, output)
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
        const val CHANNEL = "translation_channel"
        private const val ID_PROGRESS = -801
        private const val ID_RESULT = -802
        private const val ID_QUALITY = -803
        private val QUALITY_ONLY = ChapterTranslator.QUALITY_REVIEW - ChapterTranslator.FAST_REVIEW.toSet()
        private const val MAX_ATTEMPTS = 3
        private const val UNREACHABLE_RETRY_MILLIS = 15_000L
        private const val UNREACHABLE_FOREGROUND_MILLIS = 10 * 60_000L
        private const val UNREACHABLE_POLL_MILLIS = 15 * 60_000L
        private const val LONG_PAUSE_MILLIS = 20 * 60_000L

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
