package mihon.fork.translation

import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import eu.kanade.tachiyomi.util.system.notificationBuilder
import mihon.core.metro.metroGraph
import mihon.translation.pc.PcException
import tachiyomi.i18n.R
import java.util.concurrent.TimeUnit

/**
 * Looks for the PC in the background when the foreground worker stopped waiting for it (PC away, long pause,
 * time limit of the foreground service), and for translations redone on the PC. As soon as the PC answers it
 * hands over to [TranslationWorker].
 */
class PcPollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val notifications = NotificationManagerCompat.from(context)

    override suspend fun doWork(): Result {
        val graph = applicationContext.metroGraph<TranslationGraph>()
        val manager = graph.translationManager
        val pc = graph.pcTranslator
        if (!pc.isPaired()) {
            notifications.cancel(ID_WAITING)
            return Result.success()
        }
        val waiting = manager.queue.value.filter { it.waitingOnPc }
        val remote = try {
            pc.withClient { it.jobs() }
        } catch (e: PcException) {
            if (waiting.isNotEmpty()) {
                show(
                    applicationContext,
                    applicationContext.getString(R.string.translation_pc_unreachable, waiting.size),
                )
                schedule(applicationContext, UNREACHABLE_MILLIS)
            }
            return Result.success()
        }
        // The PC answers again: the worker follows it live and collects the results
        if (waiting.isNotEmpty() || remote.any { it.isDone && it.revision > 0 }) {
            TranslationWorker.start(applicationContext)
        } else {
            notifications.cancel(ID_WAITING)
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "translation_pc_poll"
        private const val ID_WAITING = -804
        const val POLL_MILLIS = 2 * 60 * 1000L
        private const val UNREACHABLE_MILLIS = 15 * 60 * 1000L

        fun dismiss(context: Context) = NotificationManagerCompat.from(context).cancel(ID_WAITING)

        /** The last state of the PC, left visible while nothing runs in the foreground. */
        fun show(context: Context, text: String) {
            TranslationWorker.ensureChannel(context)
            val notifications = NotificationManagerCompat.from(context)
            val notification = context.notificationBuilder(TranslationWorker.CHANNEL) {
                setContentTitle(context.getString(R.string.translation_pc_title))
                setContentText(text)
                setStyle(NotificationCompat.BigTextStyle().bigText(text))
                setSmallIcon(android.R.drawable.ic_popup_sync)
                setOnlyAlertOnce(true)
                setPriority(NotificationCompat.PRIORITY_LOW)
            }.build()
            if (notifications.areNotificationsEnabled()) {
                @Suppress("MissingPermission")
                notifications.notify(ID_WAITING, notification)
            }
        }

        fun schedule(context: Context, delayMillis: Long) {
            val request = OneTimeWorkRequestBuilder<PcPollWorker>()
                .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}
