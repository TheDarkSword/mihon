package mihon.fork.translation

import android.content.Context
import android.os.Build
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import mihon.translation.ApiKeyStore
import mihon.translation.TranslationPipeline
import mihon.translation.context.SeriesContext
import mihon.translation.model.TextBlock
import mihon.translation.pc.PcClient
import mihon.translation.pc.PcException
import tachiyomi.domain.manga.model.Manga
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * The translator on the reader's PC: pairing, the address on the home network, the packages prepared for it and
 * the copy of each series memory last received from it.
 */
@Inject
@SingleIn(AppScope::class)
class PcTranslator(
    private val context: Context,
    private val preferences: TranslationPreferences,
    private val storage: TranslationStorage,
) {

    private val keys by lazy { ApiKeyStore(context) }
    private val root get() = File(context.filesDir, "translation_pc")

    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    fun isPaired() = preferences.pcAddress.get().isNotBlank() && !keys.get(ApiKeyStore.PC_TOKEN).isNullOrBlank()

    fun client(): PcClient? =
        if (isPaired()) PcClient(preferences.pcAddress.get(), keys.get(ApiKeyStore.PC_TOKEN)) else null

    /** Asks the PC to show a pairing code. */
    suspend fun requestCode(address: String) = PcClient(address).startPairing(deviceName)

    suspend fun pair(address: String, serviceName: String?, code: String) {
        val token = PcClient(address).confirmPairing(deviceName, code)
        keys.set(ApiKeyStore.PC_TOKEN, token)
        preferences.pcAddress.set(address)
        preferences.pcService.set(serviceName.orEmpty())
        preferences.pcName.set(runCatching { PcClient(address, token).info().name }.getOrDefault(address))
        preferences.engine.set(TranslationJob.ENGINE_PC)
    }

    fun unpair() {
        keys.set(ApiKeyStore.PC_TOKEN, null)
        preferences.pcAddress.set("")
        preferences.pcName.set("")
        preferences.pcService.set("")
        preferences.engine.set(TranslationJob.ENGINE_GEMINI)
    }

    /** Calls the PC; when it does not answer, looks for it on the network again in case its address changed. */
    suspend fun <T> withClient(block: suspend (PcClient) -> T): T {
        val client = client() ?: throw PcException(-1, "PC not paired")
        return try {
            block(client)
        } catch (e: PcException) {
            if (!e.unreachable || !relocate()) throw e
            block(client() ?: throw e)
        }
    }

    private suspend fun relocate(): Boolean {
        val found = LanDiscovery.discover(context, PcClient.SERVICE_TYPE, RELOCATE_MILLIS)
        val target =
            found.firstOrNull { it.name == preferences.pcService.get() } ?: found.singleOrNull() ?: return false
        val address = PcClient.address(target.host, target.port)
        if (address == preferences.pcAddress.get()) return false
        preferences.pcAddress.set(address)
        return true
    }

    // Series

    /** The name of the series folder: the same series has the same memory on the phone and on the PC. */
    fun seriesKey(manga: Manga): String =
        storage.seriesDir(manga, create = true)?.name ?: DiskUtil.buildValidFilename(manga.title)

    /** The copy of the series memory last received from the PC, to tell the reader's edits apart. */
    fun base(key: String): SeriesContext? = runCatching {
        TranslationPipeline.json.decodeFromString<SeriesContext>(baseFile(key).readText())
    }.getOrNull()

    private fun baseFile(key: String) = File(root, "base/${hash(key)}.json")

    /** Sends the reader's edits to the PC and stores the merged memory and the corrections it returns. */
    suspend fun sync(manga: Manga) {
        val key = seriesKey(manga)
        val local = storage.readContext(manga) ?: SeriesContext(series = manga.title)
        val result = withClient { it.syncContext(key, local, base(key)) }
        result.context?.let { merged ->
            storage.writeContext(manga, merged)
            baseFile(key).apply { parentFile?.mkdirs() }.writeText(
                TranslationPipeline.json.encodeToString(SeriesContext.serializer(), merged),
            )
        }
        storage.writeCorrections(manga, result.corrections)
        rememberSeries(key, manga.id)
    }

    /** Which manga a series key belongs to, for translations redone from the PC. */
    fun mangaFor(key: String): Long? = series()[key]

    fun rememberSeries(key: String, mangaId: Long) {
        val known = series()
        if (known[key] == mangaId) return
        seriesFile.apply { parentFile?.mkdirs() }.writeText(
            TranslationPipeline.json.encodeToString(SERIES, known + (key to mangaId)),
        )
    }

    private val seriesFile get() = File(root, "series.json")

    private fun series(): Map<String, Long> = runCatching {
        TranslationPipeline.json.decodeFromString(SERIES, seriesFile.readText())
    }.getOrDefault(emptyMap())

    // Chapters prepared for the PC

    fun pendingDir(chapterId: Long) = File(root, "jobs/$chapterId")

    fun packageFile(chapterId: Long) = File(pendingDir(chapterId), "package.zip")

    fun saveBlocks(chapterId: Long, blocks: List<TextBlock>) {
        File(pendingDir(chapterId), BLOCKS).writeText(TranslationPipeline.json.encodeToString(BLOCK_LIST, blocks))
    }

    fun blocks(chapterId: Long): List<TextBlock>? = runCatching {
        TranslationPipeline.json.decodeFromString(BLOCK_LIST, File(pendingDir(chapterId), BLOCKS).readText())
    }.getOrNull()

    fun clearPending(chapterId: Long) {
        pendingDir(chapterId).deleteRecursively()
    }

    /** Drops what was prepared for chapters no longer in the queue. */
    fun clearPendingExcept(chapterIds: Set<Long>) {
        File(root, "jobs").listFiles()?.filter {
            it.name.toLongOrNull() !in chapterIds
        }?.forEach { it.deleteRecursively() }
    }

    private fun hash(key: String) =
        MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") {
            String.format(Locale.ROOT, "%02x", it)
        }

    companion object {
        private const val BLOCKS = "blocks.json"
        private const val RELOCATE_MILLIS = 3000L
        private val BLOCK_LIST = ListSerializer(TextBlock.serializer())
        private val SERIES = MapSerializer(String.serializer(), Long.serializer())
    }
}
