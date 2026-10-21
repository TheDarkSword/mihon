package mihon.fork.translation

import android.content.Context
import androidx.core.net.toUri
import com.hippo.unifile.UniFile
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.storage.DiskUtil
import kotlinx.serialization.Serializable
import mihon.translation.TranslationPipeline
import mihon.translation.context.SeriesContext
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.storage.service.StoragePreferences
import java.io.File

/**
 * Translated series live in `<Mihon folder>/translations/<title>/`: one CBZ per chapter, named by chapter
 * number, plus the series link and its translation context. The folder can be exported as is.
 */
@Inject
@SingleIn(AppScope::class)
class TranslationStorage(
    private val context: Context,
    private val storagePreferences: StoragePreferences,
) {

    @Serializable
    data class SeriesLink(val sourceId: Long, val mangaUrl: String, val title: String)

    private fun root(): UniFile? {
        val base = UniFile.fromUri(context, storagePreferences.baseStorageDirectory.get().toUri()) ?: return null
        return base.findFile(ROOT) ?: base.createDirectory(ROOT)
    }

    fun seriesDir(manga: Manga, create: Boolean = false): UniFile? {
        val root = root() ?: return null
        val name = DiskUtil.buildValidFilename(manga.title)
        val link = SeriesLink(manga.source, manga.url, manga.title)
        root.findFile(name)?.let { dir ->
            val stored = readLink(dir)
            if (stored == null || stored == link) return dir
            // Sources change URLs over time: same title and source is still the same series
            if (stored.sourceId == link.sourceId) {
                write(dir, LINK_FILE, TranslationPipeline.json.encodeToString(SeriesLink.serializer(), link))
                return dir
            }
        }
        // Same title from another source gets its own folder
        val fallback = "$name [${manga.source}]"
        root.findFile(fallback)?.let { return it }
        if (!create) return null
        val dir = root.createDirectory(if (root.findFile(name) == null) name else fallback) ?: return null
        write(dir, LINK_FILE, TranslationPipeline.json.encodeToString(SeriesLink.serializer(), link))
        return dir
    }

    fun chapterName(chapter: Chapter): String = label(chapter.chapterNumber) + ".cbz"

    fun chapterFile(manga: Manga, chapter: Chapter): UniFile? {
        if (chapter.chapterNumber < 0) return null
        if (chapterName(chapter) !in translated(manga)) return null
        return seriesDir(manga)?.findFile(chapterName(chapter))
    }

    fun translated(manga: Manga): Set<String> = TranslatedChapters.get(manga.id) ?: run {
        val names = seriesDir(manga)?.listFiles()?.mapNotNull { it.name?.takeIf { n -> n.endsWith(".cbz") } }
        names.orEmpty().toSet().also { TranslatedChapters.set(manga.id, it) }
    }

    fun isTranslated(manga: Manga, chapter: Chapter) =
        chapter.chapterNumber >= 0 && chapterName(chapter) in translated(manga)

    fun saveChapter(manga: Manga, chapter: Chapter, cbz: File) {
        val dir = seriesDir(manga, create = true) ?: error("No storage folder")
        val name = chapterName(chapter)
        dir.findFile(name)?.delete()
        val target = dir.createFile(name) ?: error("Cannot create $name")
        target.openOutputStream().use { out -> cbz.inputStream().use { it.copyTo(out) } }
        TranslatedChapters.set(manga.id, translated(manga) + name)
    }

    fun deleteChapter(manga: Manga, chapter: Chapter) {
        val name = chapterName(chapter)
        seriesDir(manga)?.findFile(name)?.delete()
        TranslatedChapters.set(manga.id, translated(manga) - name)
    }

    /** Removes the translated chapters of a series, keeping its link and translation context. */
    fun deleteChapters(manga: Manga) {
        seriesDir(manga)?.listFiles()?.filter { it.name?.endsWith(".cbz") == true }?.forEach { it.delete() }
        TranslatedChapters.set(manga.id, emptySet())
    }

    /** Removes the translated chapters of every series, keeping links and contexts. */
    fun deleteAllChapters() {
        root()?.listFiles()?.forEach { dir ->
            dir.listFiles()?.filter { it.name?.endsWith(".cbz") == true }?.forEach { it.delete() }
        }
        TranslatedChapters.clear()
    }

    fun readContext(manga: Manga): SeriesContext? = seriesDir(manga)?.findFile(CONTEXT_FILE)
        ?.let { file ->
            runCatching { TranslationPipeline.json.decodeFromString<SeriesContext>(read(file)) }.getOrNull()
        }

    fun writeContext(manga: Manga, ctx: SeriesContext) {
        val dir = seriesDir(manga, create = true) ?: return
        write(dir, CONTEXT_FILE, TranslationPipeline.json.encodeToString(SeriesContext.serializer(), ctx))
    }

    private fun readLink(dir: UniFile): SeriesLink? = dir.findFile(LINK_FILE)
        ?.let { runCatching { TranslationPipeline.json.decodeFromString<SeriesLink>(read(it)) }.getOrNull() }

    private fun read(file: UniFile): String = file.openInputStream().use { it.readBytes().decodeToString() }

    private fun write(dir: UniFile, name: String, text: String) {
        // Some providers append to existing files, so they are recreated
        dir.findFile(name)?.delete()
        val file = dir.createFile(name) ?: return
        file.openOutputStream().use { it.write(text.toByteArray()) }
    }

    companion object {
        private const val ROOT = "translations"
        private const val LINK_FILE = "series.json"
        private const val CONTEXT_FILE = "context.json"

        fun label(number: Double): String = if (number % 1.0 == 0.0) number.toLong().toString() else number.toString()
    }
}
