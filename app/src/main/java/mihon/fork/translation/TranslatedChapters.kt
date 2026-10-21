package mihon.fork.translation

import android.content.Context
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.kanade.tachiyomi.ui.manga.ChapterList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.metro.metroGraph
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.IconButtonTokens
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.abs

/** Translated chapter files per manga, observable so the chapter list updates as translations finish. */
object TranslatedChapters {
    private val files = mutableStateMapOf<Long, Set<String>>()
    private val manga = mutableStateMapOf<Long, Manga>()

    fun get(mangaId: Long): Set<String>? = files[mangaId]

    fun set(mangaId: Long, names: Set<String>) {
        files[mangaId] = names
    }

    fun clear() {
        files.clear()
    }

    /** The open manga, so the chapter badge can act on its series. */
    fun register(value: Manga) {
        manga[value.id] = value
    }

    fun manga(id: Long): Manga? = manga[id]

    fun isTranslated(chapter: Chapter): Boolean =
        chapter.chapterNumber >= 0 &&
            TranslationStorage.label(chapter.chapterNumber) + ".cbz" in files[chapter.mangaId].orEmpty()
}

@Composable
fun TranslatedChapterBadge(chapter: Chapter) {
    if (!TranslatedChapters.isTranslated(chapter)) return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }

    // Same height as the download indicator so both sit on the same line
    Box(modifier = Modifier.height(IconButtonTokens.StateLayerSize), contentAlignment = Alignment.Center) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(start = 4.dp)
                .clickable { confirm = true },
        ) {
            Text(
                text = stringResource(MR.strings.translation_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
            )
        }
    }

    if (confirm) {
        val label = TranslationStorage.label(chapter.chapterNumber)
        AlertDialog(
            onDismissRequest = { confirm = false },
            text = { Text(stringResource(MR.strings.translation_delete_chapter, label)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirm = false
                        val manga = TranslatedChapters.manga(chapter.mangaId) ?: return@TextButton
                        scope.launch(Dispatchers.IO) {
                            context.metroGraph<TranslationGraph>().translationStorage.deleteChapter(manga, chapter)
                        }
                    },
                ) { Text(stringResource(MR.strings.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirm = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }
}

/** Scrolls the chapter list to the next chapter to read when asked from the manga menu. */
object ChapterAutoScroll {
    private val requests = MutableSharedFlow<Long>(extraBufferCapacity = 1)

    fun request(mangaId: Long) {
        requests.tryEmit(mangaId)
    }

    /**
     * Next chapter to read: the first unread one after the last read chapter, or the first unread one when
     * nothing was read yet.
     */
    fun target(items: List<ChapterList>): Chapter? {
        val chapters = items.filterIsInstance<ChapterList.Item>().map { it.chapter }
        val lastRead = chapters.filter { it.read }.maxOfOrNull { it.chapterNumber }
        return chapters
            .filter { !it.read && (lastRead == null || it.chapterNumber > lastRead) }
            .minByOrNull { it.chapterNumber }
    }

    @Composable
    fun Effect(manga: Manga, listState: LazyListState, items: List<ChapterList>, firstChapterIndex: Int) {
        val current by rememberUpdatedState(items)
        LaunchedEffect(manga.id, listState) {
            requests.filter { it == manga.id }.collect {
                val list = current
                val target = target(list) ?: return@collect
                val index = firstChapterIndex +
                    list.indexOfFirst { it is ChapterList.Item && it.chapter.id == target.id }
                val layout = listState.layoutInfo
                val visible = layout.visibleItemsInfo.any {
                    it.index == index && it.offset >= layout.viewportStartOffset &&
                        it.offset + it.size <= layout.viewportEndOffset
                }
                if (!visible) smoothScrollTo(listState, index, firstChapterIndex)
            }
        }
    }
}

/**
 * animateScrollToItem jumps when the target is far away, because rows that were never laid out have no known
 * height. Chapter rows are all about the same height, so the distance is estimated from the visible ones and
 * covered with one continuous animation, then corrected on the exact row.
 */
private suspend fun smoothScrollTo(listState: LazyListState, index: Int, firstChapterIndex: Int) {
    val layout = listState.layoutInfo
    val rows = layout.visibleItemsInfo.filter { it.index >= firstChapterIndex }
    val rowHeight = rows.map { it.size }.average().takeIf { !it.isNaN() }?.toFloat() ?: return
    val targetOffset = layout.viewportSize.height / 3f
    val first = layout.visibleItemsInfo.firstOrNull() ?: return
    val current = first.index * rowHeight - first.offset
    val distance = index * rowHeight - targetOffset - current
    val duration = (abs(distance) / PIXELS_PER_MILLI).toInt().coerceIn(MIN_SCROLL_MILLIS, MAX_SCROLL_MILLIS)
    listState.animateScrollBy(distance, tween(durationMillis = duration, easing = FastOutSlowInEasing))
    listState.animateScrollToItem(index, -targetOffset.toInt())
}

private const val PIXELS_PER_MILLI = 6f
private const val MIN_SCROLL_MILLIS = 350
private const val MAX_SCROLL_MILLIS = 1200

/** Queues new chapters found by a library update for the series with automatic translation enabled. */
object AutoTranslation {
    fun onNewChapters(context: Context, manga: Manga, chapters: List<Chapter>) {
        val graph = context.metroGraph<TranslationGraph>()
        if (manga.id.toString() !in graph.translationPreferences.autoManga.get()) return
        if (!graph.translationManager.hasKey()) return
        graph.translationManager.enqueue(manga, chapters)
    }

    fun isEnabled(context: Context, manga: Manga) =
        manga.id.toString() in context.metroGraph<TranslationGraph>().translationPreferences.autoManga.get()

    fun setEnabled(context: Context, manga: Manga, enabled: Boolean) {
        val preference = context.metroGraph<TranslationGraph>().translationPreferences.autoManga
        val ids = preference.get()
        preference.set(if (enabled) ids + manga.id.toString() else ids - manga.id.toString())
    }
}

internal suspend fun deleteTranslations(context: Context, manga: Manga) = withContext(Dispatchers.IO) {
    context.metroGraph<TranslationGraph>().translationStorage.deleteChapters(manga)
}
