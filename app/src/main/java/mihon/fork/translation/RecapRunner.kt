package mihon.fork.translation

import android.content.Context
import android.graphics.BitmapRegionDecoder
import eu.kanade.domain.chapter.model.toSChapter
import eu.kanade.tachiyomi.data.download.DownloadProvider
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import mihon.translation.CheckpointCache
import mihon.translation.context.ChapterText
import mihon.translation.context.ContextBuilder
import mihon.translation.context.SeriesContext
import mihon.translation.context.TextExtractor
import mihon.translation.image.Strip
import mihon.translation.llm.GeminiClient
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.source.service.SourceManager
import java.io.File
import java.util.zip.ZipInputStream

/** Reads a sample of already read chapters and turns them into the series context. */
class RecapRunner(
    private val context: Context,
    private val sourceManager: SourceManager,
    private val getManga: GetManga,
    private val getChaptersByMangaId: GetChaptersByMangaId,
    private val downloadProvider: DownloadProvider,
    private val storage: TranslationStorage,
    private val client: GeminiClient,
) {

    suspend fun run(manga: Manga, request: RecapRequest, progress: (String) -> Unit) {
        val italianSource = sourceManager.get(request.sourceId) as? HttpSource ?: error("Italian source not installed")
        val englishSource = sourceManager.getOrStub(manga.source) as? HttpSource
        val reference = SManga.create().apply {
            url = request.mangaUrl
            title = request.title
        }
        val italianChapters = italianSource.getMangaUpdate(reference, emptyList(), false, true).chapters
            .filter { it.chapter_number >= 0 && it.chapter_number <= request.until }
            .distinctBy { it.chapter_number }
            .sortedBy { it.chapter_number }
        if (italianChapters.isEmpty()) error("No Italian chapters found")
        val englishChapters = getChaptersByMangaId.await(manga.id).associateBy { it.chapterNumber.toFloat() }
        val localManga = request.localMangaId?.let { getManga.await(it) }
        val localChapters = localManga?.let { local ->
            getChaptersByMangaId.await(local.id).associateBy { it.chapterNumber.toFloat() }
        }.orEmpty()

        val picked = sample(italianChapters.map { it.chapter_number }, request.precision).toSet()
        val alignedNumbers = italianChapters.map {
            it.chapter_number
        }.filter { it in englishChapters }.takeLast(ALIGNED)

        val checkpoints = CheckpointCache(File(context.cacheDir, "translation")).entry("recap:${manga.id}")
        val italian = mutableListOf<ChapterText>()
        val aligned = mutableListOf<Pair<ChapterText, ChapterText>>()
        TextExtractor(context).use { extractor ->
            val toRead = italianChapters.filter { it.chapter_number in picked || it.chapter_number in alignedNumbers }
            toRead.forEachIndexed { index, chapter ->
                val number = chapter.chapter_number
                progress("Reading Italian chapter ${label(number)} (${index + 1}/${toRead.size})")
                val text = checkpoints.load("it-$number", ChapterText.serializer())
                    ?: extractor.read(
                        number,
                        localManga?.let { downloaded(it, italianSource, localChapters[number]) }
                            ?: pages(italianSource, chapter),
                    )
                        .also { checkpoints.save("it-$number", ChapterText.serializer(), it) }
                if (number in picked) italian += text
                if (number in alignedNumbers && englishSource != null) {
                    progress("Reading English chapter ${label(number)}")
                    val english = checkpoints.load("en-$number", ChapterText.serializer())
                        ?: extractor.read(
                            number,
                            downloaded(manga, englishSource, englishChapters[number])
                                ?: pages(englishSource, englishChapters.getValue(number).toSChapter()),
                        )
                            .also { checkpoints.save("en-$number", ChapterText.serializer(), it) }
                    aligned += english to text
                }
            }
        }

        val base = storage.readContext(manga) ?: SeriesContext(series = manga.title)
        val built = ContextBuilder(client).build(base, request.until.toFloat(), aligned, italian, progress)
        storage.writeContext(manga, built)
        checkpoints.delete()
    }

    /** Pages of a chapter already downloaded by Mihon, as a folder of images or a CBZ. */
    private suspend fun downloaded(manga: Manga, source: HttpSource, chapter: Chapter?): List<Strip.PageImage>? {
        chapter ?: return null
        val dir = downloadProvider.findChapterDir(chapter.name, chapter.scanlator, chapter.url, manga.title, source)
            ?: return null
        val images = if (dir.isDirectory) {
            dir.listFiles().orEmpty()
                .filter { it.isFile && it.name?.substringAfterLast('.')?.lowercase() in IMAGES }
                .sortedBy { it.name }
                .map { file -> file.name.orEmpty() to file.openInputStream().use { it.readBytes() } }
        } else {
            buildList {
                ZipInputStream(dir.openInputStream().buffered()).use { zip ->
                    generateSequence { zip.nextEntry }.forEach { entry ->
                        if (!entry.isDirectory && entry.name.substringAfterLast('.').lowercase() in IMAGES) {
                            add(entry.name to zip.readBytes())
                        }
                    }
                }
            }.sortedBy { it.first }
        }
        return images.takeIf { it.isNotEmpty() }?.map { (name, bytes) ->
            Strip.PageImage(name) { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size) }
        }
    }

    /** Page images kept in memory and decoded region by region, downloaded a few at a time. */
    private suspend fun pages(source: HttpSource, chapter: SChapter): List<Strip.PageImage> = coroutineScope {
        val permits = Semaphore(PARALLEL_DOWNLOADS)
        source.getPageList(chapter).map { page ->
            async {
                permits.withPermit {
                    if (page.imageUrl.isNullOrEmpty()) page.imageUrl = source.getImageUrl(page)
                    val bytes = source.getImage(page).use { it.body.bytes() }
                    Strip.PageImage("${page.index}") { BitmapRegionDecoder.newInstance(bytes, 0, bytes.size) }
                }
            }
        }.awaitAll()
    }

    companion object {
        private const val ALIGNED = 2
        private const val PARALLEL_DOWNLOADS = 6
        private val IMAGES = setOf("jpg", "jpeg", "png", "webp")

        /** Approximate minutes per chapter, dominated by slow image servers. */
        const val MINUTES_PER_CHAPTER = 1.5

        /** The first chapters introduce the cast, the last ones lead into the translation, the middle is sparser. */
        fun sample(numbers: List<Float>, precision: Int): List<Float> {
            val (head, tail, step) = when (precision) {
                0 -> Triple(2, 5, 8)
                1 -> Triple(3, 8, 4)
                else -> Triple(3, 12, 2)
            }
            val middle = numbers.drop(head).dropLast(tail).filterIndexed { i, _ -> i % step == 0 }
            return (numbers.take(head) + middle + numbers.takeLast(tail)).distinct()
        }

        fun label(number: Float): String = TranslationStorage.label(number.toDouble())
    }
}
