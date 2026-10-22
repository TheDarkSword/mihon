package mihon.fork.translation

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.icerock.moko.resources.StringResource
import eu.kanade.presentation.components.AdaptiveSheet
import eu.kanade.presentation.components.AppBar
import eu.kanade.tachiyomi.ui.manga.MangaViewModel
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.metro.metroGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Autorenew
import mihon.icons.materialsymbols.rounded.Book
import mihon.icons.materialsymbols.rounded.DeleteSweep
import mihon.icons.materialsymbols.rounded.DoneAll
import mihon.icons.materialsymbols.rounded.EditNote
import mihon.icons.materialsymbols.rounded.FormatListNumbered
import mihon.icons.materialsymbols.rounded.Numbers
import mihon.icons.materialsymbols.rounded.Palette
import mihon.icons.materialsymbols.rounded.Share
import mihon.icons.materialsymbols.rounded.SkipNext
import mihon.icons.materialsymbols.rounded.Translate
import mihon.icons.materialsymbols.rounded.TravelExplore
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

/** Extra manga toolbar actions, registered by the manga screen and read by the toolbar. */
object MangaToolbarExtras {
    data class Actions(val selection: List<AppBar.AppBarAction>, val overflow: List<AppBar.AppBarAction>)

    private val actions = mutableStateMapOf<String, Actions>()

    fun selection(title: String) = actions[title]?.selection.orEmpty()

    fun overflow(title: String) = actions[title]?.overflow.orEmpty()

    internal fun set(title: String, value: Actions) {
        actions[title] = value
    }

    internal fun remove(title: String) {
        actions.remove(title)
    }
}

@Composable
fun MangaTranslationActions(viewModel: MangaViewModel, state: MangaViewModel.State.Success) {
    val context = LocalContext.current
    val navigator = LocalNavigator.currentOrThrow
    val graph = remember { context.metroGraph<TranslationGraph>() }
    val manager = graph.translationManager
    val storage = graph.translationStorage
    val scope = rememberCoroutineScope()
    val manga = state.manga
    var showDialog by remember { mutableStateOf(false) }

    var confirmDeleteAll by remember { mutableStateOf(false) }
    var showRecap by remember { mutableStateOf(false) }
    val exportLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val count = withContext(Dispatchers.IO) { TranslationTransfer(context, storage).export(manga, uri) }
                context.toast(context.getString(tachiyomi.i18n.R.string.translation_exported, count))
            }
        }

    LaunchedEffect(manga.id) {
        TranslatedChapters.register(manga)
        withContext(Dispatchers.IO) {
            storage.translated(manga)
            manager.resumeIfNeeded()
        }
    }

    fun enqueue(pick: (List<Chapter>, Set<String>) -> List<Chapter>) {
        if (!manager.hasKey()) {
            context.toast(MR.strings.translation_missing_key)
            return
        }
        val chapters = state.chapters.map { it.chapter }
        scope.launch {
            val result =
                withContext(Dispatchers.IO) { manager.enqueue(manga, pick(chapters, storage.translated(manga))) }
            val message = when {
                result.added > 0 -> context.getString(tachiyomi.i18n.R.string.translation_queued, result.added)
                result.alreadyQueued > 0 ->
                    context.getString(tachiyomi.i18n.R.string.translation_already_queued, result.alreadyQueued)
                else -> context.getString(tachiyomi.i18n.R.string.translation_nothing_to_queue)
            }
            context.toast(message)
        }
    }

    val translateTitle = stringResource(MR.strings.action_translate)
    val moreTitle = stringResource(MR.strings.action_translate_more)
    val autoScrollTitle = stringResource(MR.strings.action_auto_scroll)
    val selected = state.selectedChapters
    val actions = remember(selected, translateTitle) {
        MangaToolbarExtras.Actions(
            selection = listOf(
                AppBar.Action(
                    title = translateTitle,
                    icon = MaterialSymbols.Rounded.Translate,
                    onClick = {
                        val ids = selected.map { it.chapter.id }.toSet()
                        enqueue { chapters, _ -> chapters.filter { it.id in ids } }
                        viewModel.toggleAllSelection(false)
                    },
                ),
            ),
            overflow = listOf(
                AppBar.OverflowAction(title = moreTitle, onClick = { showDialog = true }),
                AppBar.OverflowAction(title = autoScrollTitle, onClick = { ChapterAutoScroll.request(manga.id) }),
            ),
        )
    }
    DisposableEffect(manga.title, actions) {
        MangaToolbarExtras.set(manga.title, actions)
        onDispose { MangaToolbarExtras.remove(manga.title) }
    }

    if (showRecap) {
        RecapDialog(
            manga = manga,
            onDismiss = { showRecap = false },
            onConfirm = { request ->
                showRecap = false
                if (!manager.hasKey()) {
                    context.toast(MR.strings.translation_missing_key)
                } else {
                    scope.launch {
                        withContext(Dispatchers.IO) { manager.enqueueRecap(manga, request) }
                        context.toast(MR.strings.translation_recap_queued)
                    }
                }
            },
        )
    }

    if (confirmDeleteAll) {
        AlertDialog(
            onDismissRequest = { confirmDeleteAll = false },
            text = { Text(stringResource(MR.strings.translation_delete_all_manga_confirm)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDeleteAll = false
                        scope.launch { deleteTranslations(context, manga) }
                    },
                ) { Text(stringResource(MR.strings.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeleteAll = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }

    if (showDialog) {
        TranslateDialog(
            showTranslation = manager.showsTranslation(manga),
            onShowTranslation = { manager.setShowTranslation(manga, it) },
            autoTranslate = AutoTranslation.isEnabled(context, manga),
            onDeleteAll = {
                showDialog = false
                confirmDeleteAll = true
            },
            onRecap = {
                showDialog = false
                showRecap = true
            },
            onRerender = {
                showDialog = false
                val chapters = state.chapters.map { it.chapter }
                scope.launch {
                    val count = withContext(Dispatchers.IO) { manager.enqueueRender(manga, chapters) }
                    context.toast(context.getString(tachiyomi.i18n.R.string.translation_rerender_queued, count))
                }
            },
            onExport = {
                showDialog = false
                exportLauncher.launch("${manga.title}.zip")
            },
            onContext = {
                showDialog = false
                navigator.push(SeriesContextScreen(manga.id))
            },
            onAutoTranslate = { AutoTranslation.setEnabled(context, manga, it) },
            onDismiss = { showDialog = false },
            onPick = { pick ->
                showDialog = false
                enqueue(pick)
            },
        )
    }
}

@Composable
private fun TranslateDialog(
    showTranslation: Boolean,
    onShowTranslation: (Boolean) -> Unit,
    autoTranslate: Boolean,
    onDeleteAll: () -> Unit,
    onRecap: () -> Unit,
    onContext: () -> Unit,
    onExport: () -> Unit,
    onRerender: () -> Unit,
    onAutoTranslate: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onPick: ((List<Chapter>, Set<String>) -> List<Chapter>) -> Unit,
) {
    var show by remember { mutableStateOf(showTranslation) }
    var auto by remember { mutableStateOf(autoTranslate) }
    var rangeOpen by remember { mutableStateOf(false) }
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    AdaptiveSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) {
            SheetSection(stringResource(MR.strings.translation_section_translate))
            SheetItem(MaterialSymbols.Rounded.DoneAll, MR.strings.translate_all, MR.strings.translate_all_desc) {
                onPick { chapters, _ -> chapters }
            }
            SheetItem(MaterialSymbols.Rounded.Book, MR.strings.translate_unread, MR.strings.translate_unread_desc) {
                onPick { chapters, _ -> chapters.filter { !it.read } }
            }
            SheetItem(MaterialSymbols.Rounded.SkipNext, MR.strings.translate_new, MR.strings.translate_new_desc) {
                onPick(::afterLastTranslated)
            }
            SheetItem(
                MaterialSymbols.Rounded.FormatListNumbered,
                MR.strings.translate_missing,
                MR.strings.translate_missing_desc,
            ) { onPick(::missingBetweenTranslated) }
            SheetItem(MaterialSymbols.Rounded.Numbers, MR.strings.translate_range, MR.strings.translate_range_desc) {
                rangeOpen = !rangeOpen
            }
            if (rangeOpen) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                ) {
                    OutlinedTextField(
                        value = from,
                        onValueChange = { from = it },
                        label = { Text(stringResource(MR.strings.translate_range_from)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = to,
                        onValueChange = { to = it },
                        label = { Text(stringResource(MR.strings.translate_range_to)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        enabled = from.toDoubleOrNull() != null && to.toDoubleOrNull() != null,
                        onClick = {
                            val range = from.toDouble()..to.toDouble()
                            onPick { chapters, _ -> chapters.filter { it.chapterNumber in range } }
                        },
                    ) { Text(stringResource(MR.strings.action_translate)) }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SheetSection(stringResource(MR.strings.translation_section_series))
            SheetItem(
                MaterialSymbols.Rounded.EditNote,
                MR.strings.translation_context,
                MR.strings.translation_context_desc,

                onClick = onContext,
            )
            SheetItem(
                MaterialSymbols.Rounded.TravelExplore,
                MR.strings.translation_recap,
                MR.strings.translation_recap_summary,

                onClick = onRecap,
            )
            SheetItem(
                MaterialSymbols.Rounded.Palette,
                MR.strings.translation_rerender,
                MR.strings.translation_rerender_desc,

                onClick = onRerender,
            )
            SheetItem(
                MaterialSymbols.Rounded.Share,
                MR.strings.translation_export,
                MR.strings.translation_export_desc,

                onClick = onExport,
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SheetSection(stringResource(MR.strings.translation_section_options))
            SheetSwitch(
                MaterialSymbols.Rounded.Translate,
                MR.strings.translation_show,
                MR.strings.translation_show_desc,
                show,
            ) {
                show = it
                onShowTranslation(it)
            }
            SheetSwitch(
                MaterialSymbols.Rounded.Autorenew,
                MR.strings.translation_auto,
                MR.strings.translation_auto_desc,
                auto,
            ) {
                auto = it
                onAutoTranslate(it)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SheetItem(
                icon = MaterialSymbols.Rounded.DeleteSweep,
                title = MR.strings.translation_delete_all_manga,
                description = MR.strings.translation_delete_all_desc,
                tint = MaterialTheme.colorScheme.error,
                onClick = onDeleteAll,
            )
        }
    }
}

@Composable
private fun SheetSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun SheetItem(
    icon: ImageVector,
    title: StringResource,
    description: StringResource,
    tint: Color = Color.Unspecified,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (tint ==
                Color.Unspecified
            ) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                tint
            },
        )
        Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
            Text(
                stringResource(title),
                style = MaterialTheme.typography.bodyLarge,
                color = if (tint ==
                    Color.Unspecified
                ) {
                    Color.Unspecified
                } else {
                    tint
                },
            )
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SheetSwitch(
    icon: ImageVector,
    title: StringResource,
    description: StringResource,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clickable {
            onChange(!checked)
        }.padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun translatedNumbers(names: Set<String>) = names.mapNotNull { it.removeSuffix(".cbz").toDoubleOrNull() }

/** Chapters after the last translated one, or everything when nothing is translated yet. */
private fun afterLastTranslated(chapters: List<Chapter>, translated: Set<String>): List<Chapter> {
    val last = translatedNumbers(translated).maxOrNull() ?: return chapters
    return chapters.filter { it.chapterNumber > last }
}

/** Gaps between the first and the last translated chapter. */
private fun missingBetweenTranslated(chapters: List<Chapter>, translated: Set<String>): List<Chapter> {
    val numbers = translatedNumbers(translated)
    val first = numbers.minOrNull() ?: return emptyList()
    val last = numbers.max()
    return chapters.filter { it.chapterNumber in first..last }
}
