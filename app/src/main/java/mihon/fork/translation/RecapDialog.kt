package mihon.fork.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import eu.kanade.domain.manga.model.toSManga
import eu.kanade.tachiyomi.source.model.SManga
import eu.kanade.tachiyomi.source.online.HttpSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.app.di.appGraph
import mihon.core.metro.metroGraph
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import kotlin.math.ceil

@Composable
fun RecapDialog(manga: Manga, onDismiss: () -> Unit, onConfirm: (RecapRequest) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sourceManager = remember { context.appGraph.sourceManager }
    var sources by remember { mutableStateOf<List<HttpSource>?>(null) }
    var source by remember { mutableStateOf<HttpSource?>(null) }
    var query by remember { mutableStateOf(manga.title) }
    var results by remember { mutableStateOf<List<SManga>>(emptyList()) }
    var picked by remember { mutableStateOf<SManga?>(null) }
    var numbers by remember { mutableStateOf<List<Float>>(emptyList()) }
    var until by remember { mutableStateOf("") }
    var precision by remember { mutableIntStateOf(1) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var fromLibrary by remember { mutableStateOf(true) }
    var library by remember { mutableStateOf<List<Pair<Manga, HttpSource>>>(emptyList()) }
    var localMangaId by remember { mutableStateOf<Long?>(null) }
    var filter by remember { mutableStateOf("") }
    val graph = remember { context.metroGraph<TranslationGraph>() }

    LaunchedEffect(Unit) {
        val italian = withContext(Dispatchers.IO) { sourceManager.getOnlineSources().filter { it.lang == "it" } }
        sources = italian
        source = italian.firstOrNull { it.name.contains("MangaWorld", ignoreCase = true) } ?: italian.firstOrNull()
        val byId = italian.associateBy { it.id }
        library = withContext(Dispatchers.IO) { graph.getFavorites.await() }
            .mapNotNull { m -> byId[m.source]?.let { m to it } }
            .sortedBy { it.first.title.lowercase() }
        if (library.isEmpty()) fromLibrary = false
    }

    fun load(block: suspend () -> Unit) {
        scope.launch {
            loading = true
            error = null
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: Exception) {
                error = e.message ?: e::class.simpleName
            }
            loading = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.translation_recap)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(stringResource(MR.strings.translation_recap_summary), style = MaterialTheme.typography.bodySmall)
                val available = sources
                when {
                    available == null -> LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    available.isEmpty() -> Text(stringResource(MR.strings.translation_recap_no_sources))
                    picked == null && fromLibrary -> {
                        ModeChips(fromLibrary = true, onLibrary = {}, onSearch = { fromLibrary = false })
                        if (library.isEmpty()) {
                            Text(stringResource(MR.strings.translation_recap_library_empty))
                        } else {
                            OutlinedTextField(
                                value = filter,
                                onValueChange = { filter = it },
                                placeholder = { Text(stringResource(MR.strings.translation_search)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        library.filter { filter.isBlank() || it.first.title.contains(filter, ignoreCase = true) }
                            .forEach { (item, itemSource) ->
                                TextButton(
                                    onClick = {
                                        source = itemSource
                                        picked = item.toSManga()
                                        localMangaId = item.id
                                        load {
                                            numbers = graph.getChaptersByMangaId.await(item.id)
                                                .map { it.chapterNumber.toFloat() }
                                                .filter { it >= 0 }
                                                .distinct()
                                                .sorted()
                                            until = numbers.lastOrNull()?.let { RecapRunner.label(it) }.orEmpty()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("${item.title} · ${itemSource.name}") }
                            }
                    }
                    picked == null -> {
                        if (library.isNotEmpty()) {
                            ModeChips(fromLibrary = false, onLibrary = { fromLibrary = true }, onSearch = {})
                        }
                        Text(
                            stringResource(MR.strings.translation_recap_source),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            available.forEach { s ->
                                FilterChip(selected = s == source, onClick = { source = s }, label = { Text(s.name) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = !loading && source != null && query.isNotBlank(),
                                onClick = {
                                    val s = source ?: return@TextButton
                                    load {
                                        results = s.getSearchManga(1, query, s.getFilterList()).mangas
                                            .sortedBy { it.title.lowercase() }
                                    }
                                },
                            ) { Text(stringResource(MR.strings.translation_recap_search)) }
                        }
                        results.forEach { result ->
                            TextButton(
                                onClick = {
                                    val s = source ?: return@TextButton
                                    picked = result
                                    load {
                                        numbers = s.getMangaUpdate(result, emptyList(), false, true).chapters
                                            .map { it.chapter_number }
                                            .filter { it >= 0 }
                                            .distinct()
                                            .sorted()
                                        until = numbers.lastOrNull()?.let { RecapRunner.label(it) }.orEmpty()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(result.title) }
                        }
                    }
                    else -> {
                        Text(picked!!.title, style = MaterialTheme.typography.titleSmall)
                        if (numbers.isNotEmpty()) {
                            Text(
                                stringResource(
                                    MR.strings.translation_recap_chapters,
                                    numbers.size,
                                    RecapRunner.label(numbers.last()),
                                ),
                            )
                        }
                        OutlinedTextField(
                            value = until,
                            onValueChange = { until = it },
                            label = { Text(stringResource(MR.strings.translation_recap_read_until)) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                        )
                        Text(
                            stringResource(MR.strings.translation_recap_precision),
                            style = MaterialTheme.typography.labelLarge,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                MR.strings.translation_recap_low,
                                MR.strings.translation_recap_medium,
                                MR.strings.translation_recap_high,
                            ).forEachIndexed { index, label ->
                                FilterChip(
                                    selected = precision == index,
                                    onClick = { precision = index },
                                    label = { Text(stringResource(label)) },
                                )
                            }
                        }
                        val limit = until.toFloatOrNull()
                        if (limit != null && numbers.isNotEmpty()) {
                            val count = RecapRunner.sample(numbers.filter { it <= limit }, precision).size + 2
                            val minutes = ceil(count * RecapRunner.MINUTES_PER_CHAPTER).toInt()
                            Text(stringResource(MR.strings.translation_recap_estimate, count, minutes))
                        }
                    }
                }
                if (loading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(
                enabled = picked != null && until.toDoubleOrNull() != null && numbers.isNotEmpty() && !loading,
                onClick = {
                    val s = source ?: return@TextButton
                    val reference = picked ?: return@TextButton
                    onConfirm(
                        RecapRequest(s.id, reference.url, reference.title, until.toDouble(), precision, localMangaId),
                    )
                },
            ) { Text(stringResource(MR.strings.translation_recap_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun ModeChips(fromLibrary: Boolean, onLibrary: () -> Unit, onSearch: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = fromLibrary,
            onClick = onLibrary,
            label = { Text(stringResource(MR.strings.translation_recap_from_library)) },
        )
        FilterChip(
            selected = !fromLibrary,
            onClick = onSearch,
            label = { Text(stringResource(MR.strings.translation_recap_from_search)) },
        )
    }
}
