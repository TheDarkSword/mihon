package mihon.fork.translation

import android.content.Context
import android.net.Uri
import mihon.translation.TranslationPipeline
import mihon.translation.context.SeriesContext
import tachiyomi.domain.manga.model.Manga
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Moves a translated series in and out of the device as one ZIP: chapter CBZs, series link and context. */
class TranslationTransfer(private val context: Context, private val storage: TranslationStorage) {

    /** What an export contains, read before anything is written. */
    data class Preview(
        val link: TranslationStorage.SeriesLink,
        val chapters: List<String>,
        val conflicts: List<String>,
        val context: SeriesContext?,
    )

    fun export(manga: Manga, target: Uri): Int {
        val dir = storage.seriesDir(manga) ?: return 0
        val files = dir.listFiles().orEmpty().filter { it.isFile }
        val out = context.contentResolver.openOutputStream(target) ?: error("Cannot write the file")
        ZipOutputStream(out.buffered()).use { zip ->
            // CBZs are already compressed
            zip.setLevel(0)
            files.forEach { file ->
                zip.putNextEntry(ZipEntry(file.name))
                file.openInputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return files.count { it.name?.endsWith(".cbz") == true }
    }

    fun preview(source: Uri): Preview? {
        var link: TranslationStorage.SeriesLink? = null
        var series: SeriesContext? = null
        val chapters = mutableListOf<String>()
        read(source) { name, zip ->
            when {
                name == TranslationStorage.LINK_FILE -> link = runCatching {
                    TranslationPipeline.json.decodeFromString<TranslationStorage.SeriesLink>(
                        zip.readBytes().decodeToString(),
                    )
                }.getOrNull()
                name == TranslationStorage.CONTEXT_FILE -> series = runCatching {
                    TranslationPipeline.json.decodeFromString<SeriesContext>(zip.readBytes().decodeToString())
                }.getOrNull()
                name.endsWith(".cbz") -> chapters += name
            }
        }
        val found = link ?: return null
        val existing = storage.seriesDir(found)?.listFiles().orEmpty().mapNotNull { it.name }.toSet()
        return Preview(
            found,
            chapters.sortedBy { it.removeSuffix(".cbz").toDoubleOrNull() },
            chapters.filter {
                it in
                    existing
            },
            series,
        )
    }

    /** Copies the chapters, overwriting only the conflicting ones listed in [overwrite], then merges contexts. */
    fun import(source: Uri, preview: Preview, overwrite: Set<String>): Int {
        val dir = storage.seriesDir(preview.link, create = true) ?: error("No storage folder")
        var imported = 0
        read(source) { name, zip ->
            if (!name.endsWith(".cbz") || (name in preview.conflicts && name !in overwrite)) return@read
            dir.findFile(name)?.delete()
            val file = dir.createFile(name) ?: return@read
            file.openOutputStream().use { zip.copyTo(it) }
            imported++
        }
        preview.context?.let { incoming ->
            val current = storage.readContext(dir)
            storage.writeContext(dir, if (current == null) incoming else merge(current, incoming))
        }
        TranslatedChapters.clear()
        return imported
    }

    private fun read(source: Uri, block: (String, ZipInputStream) -> Unit) {
        val input = context.contentResolver.openInputStream(source) ?: error("Cannot read the file")
        ZipInputStream(input.buffered()).use { zip ->
            generateSequence {
                zip.nextEntry
            }.filter { !it.isDirectory }.forEach { block(it.name.substringAfterLast('/'), zip) }
        }
    }

    companion object {
        /** The context already on the device wins: incoming entries only fill what is missing. */
        fun merge(current: SeriesContext, incoming: SeriesContext): SeriesContext {
            val terms = current.glossary.map { it.en.lowercase() }.toSet()
            val names = current.characters.map { it.name.lowercase() }.toSet()
            val phrases = current.tm.map { it.en.lowercase() }.toSet()
            val chapters = current.chapters.map { it.n }.toSet()
            return current.copy(
                version = maxOf(current.version, incoming.version) + 1,
                glossary = current.glossary + incoming.glossary.filter { it.en.lowercase() !in terms },
                characters = current.characters + incoming.characters.filter { it.name.lowercase() !in names },
                tm = current.tm + incoming.tm.filter { it.en.lowercase() !in phrases },
                chapters = (current.chapters + incoming.chapters.filter { it.n !in chapters }).sortedBy { it.n },
                arcSummary = current.arcSummary.ifBlank { incoming.arcSummary },
                arcUntil = if (current.arcSummary.isBlank()) incoming.arcUntil else current.arcUntil,
            )
        }
    }
}
