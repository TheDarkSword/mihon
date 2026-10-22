package mihon.fork.translation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.metro.metroGraph
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.translation.context.Address
import mihon.translation.context.Character
import mihon.translation.context.GlossaryEntry
import mihon.translation.context.SeriesContext
import mihon.translation.context.label
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/** Lets the reader correct and lock the glossary, the characters and the story memory of a series. */
class SeriesContextScreen(private val mangaId: Long) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val graph = remember { context.metroGraph<TranslationGraph>() }
        var manga by remember { mutableStateOf<Manga?>(null) }
        var series by remember { mutableStateOf<SeriesContext?>(null) }
        var tab by remember { mutableIntStateOf(0) }
        var editingTerm by remember { mutableStateOf<GlossaryEntry?>(null) }
        var addingTerm by remember { mutableStateOf(false) }
        var editingCharacter by remember { mutableStateOf<Character?>(null) }
        var query by remember { mutableStateOf("") }

        LaunchedEffect(mangaId) {
            withContext(Dispatchers.IO) {
                val m = graph.getManga.await(mangaId) ?: return@withContext
                manga = m
                series = graph.translationStorage.readContext(m) ?: SeriesContext(series = m.title)
            }
        }

        // Every change is written right away: the worker reads the file before each chapter
        fun update(change: (SeriesContext) -> SeriesContext) {
            val m = manga ?: return
            val current = series ?: return
            val next = change(current).let { it.copy(version = it.version + 1) }
            series = next
            scope.launch(Dispatchers.IO) { graph.translationStorage.writeContext(m, next) }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.translation_context),
                    subtitle = manga?.title,
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
            floatingActionButton = {
                if (tab == 0) {
                    FloatingActionButton(onClick = { addingTerm = true }) {
                        Icon(
                            MaterialSymbols.Rounded.Add,
                            contentDescription = stringResource(MR.strings.translation_add_term),
                        )
                    }
                }
            },
        ) { padding ->
            val ctx = series
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf(
                        MR.strings.translation_tab_glossary,
                        MR.strings.translation_tab_characters,
                        MR.strings.translation_tab_story,
                    ).forEachIndexed { index, title ->
                        Tab(selected = tab == index, onClick = { tab = index }, text = { Text(stringResource(title)) })
                    }
                }
                if (ctx == null) return@Column
                when (tab) {
                    0 -> GlossaryList(ctx.glossary, query, { query = it }) { editingTerm = it }
                    1 -> CharacterList(ctx.characters, query, { query = it }) { editingCharacter = it }
                    else -> StoryTab(ctx) { arc -> update { it.copy(arcSummary = arc) } }
                }
            }
        }

        editingTerm?.let { entry ->
            TermDialog(
                entry = entry,
                isNew = false,
                onDismiss = { editingTerm = null },
                onDelete = {
                    update { c -> c.copy(glossary = c.glossary.filterNot { it.en == entry.en }) }
                    editingTerm = null
                },
                onSave = { saved ->
                    update { c -> c.copy(glossary = c.glossary.map { if (it.en == entry.en) saved else it }) }
                    editingTerm = null
                },
            )
        }
        if (addingTerm) {
            TermDialog(
                entry = GlossaryEntry(en = "", it = "", locked = true),
                isNew = true,
                onDismiss = { addingTerm = false },
                onDelete = { addingTerm = false },
                onSave = { saved ->
                    update { c -> c.copy(glossary = c.glossary.filterNot { it.en.equals(saved.en, true) } + saved) }
                    addingTerm = false
                },
            )
        }
        editingCharacter?.let { character ->
            CharacterDialog(
                character = character,
                others = series?.characters.orEmpty().filter { it.name != character.name },
                onDismiss = { editingCharacter = null },
                onDelete = {
                    update { c -> c.copy(characters = c.characters.filterNot { it.name == character.name }) }
                    editingCharacter = null
                },
                onSave = { saved ->
                    update { c ->
                        c.copy(
                            characters = c.characters.map {
                                if (it.name ==
                                    character.name
                                ) {
                                    saved
                                } else {
                                    it
                                }
                            },
                        )
                    }
                    editingCharacter = null
                },
                onMerge = { target ->
                    update { c -> c.copy(characters = merge(c.characters, character, target)) }
                    editingCharacter = null
                },
            )
        }
    }

    /** Folds a duplicate into [target]: its relations are kept and the duplicate's name joins the role. */
    private fun merge(all: List<Character>, duplicate: Character, target: Character): List<Character> {
        val known = target.addresses.map { it.to.lowercase() }.toSet()
        val merged = target.copy(
            gender = target.gender?.takeUnless { it == "unknown" } ?: duplicate.gender,
            role = target.role ?: duplicate.role,
            speech = target.speech ?: duplicate.speech,
            addresses = target.addresses + duplicate.addresses.filter { it.to.lowercase() !in known },
            hits = target.hits + duplicate.hits,
            lastSeen = maxOf(target.lastSeen, duplicate.lastSeen),
            firstSeen = minOf(target.firstSeen, duplicate.firstSeen),
        )
        return all.filterNot { it.name == duplicate.name }.map { if (it.name == target.name) merged else it }
    }
}

@Composable
private fun GlossaryList(
    entries: List<GlossaryEntry>,
    query: String,
    onQuery: (String) -> Unit,
    onClick: (GlossaryEntry) -> Unit,
) {
    val shown = entries
        .filter { query.isBlank() || it.en.contains(query, true) || it.it.contains(query, true) }
        .sortedBy { it.en.lowercase() }
    LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
        item { SearchField(query, onQuery) }
        if (shown.isEmpty()) item { Empty() }
        items(shown, key = { it.en }) { entry ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    onClick(entry)
                }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("${entry.en} → ${entry.target}", style = MaterialTheme.typography.bodyLarge)
                    Text(entry.kind, style = MaterialTheme.typography.bodySmall)
                }
                if (entry.locked) Text("🔒")
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun CharacterList(
    characters: List<Character>,
    query: String,
    onQuery: (String) -> Unit,
    onClick: (Character) -> Unit,
) {
    val shown = characters
        .filter { query.isBlank() || it.name.contains(query, true) || it.role.orEmpty().contains(query, true) }
        .sortedBy { it.name.lowercase() }
    LazyColumn {
        item { SearchField(query, onQuery) }
        if (shown.isEmpty()) item { Empty() }
        items(shown, key = { it.name }) { character ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable {
                    onClick(character)
                }.padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(character.name, style = MaterialTheme.typography.bodyLarge)
                    val details = listOfNotNull(character.gender, character.role).joinToString(" · ")
                    if (details.isNotBlank()) Text(details, style = MaterialTheme.typography.bodySmall)
                    if (character.addresses.isNotEmpty()) {
                        Text(
                            character.addresses.joinToString { "${it.form} → ${it.to}" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                if (character.locked) Text("🔒")
            }
            HorizontalDivider()
        }
    }
}

@Composable
private fun StoryTab(ctx: SeriesContext, onArc: (String) -> Unit) {
    var arc by remember(ctx.arcSummary) { mutableStateOf(ctx.arcSummary) }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(MR.strings.translation_story_arc), style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(value = arc, onValueChange = { arc = it }, modifier = Modifier.fillMaxWidth())
        TextButton(enabled = arc != ctx.arcSummary, onClick = {
            onArc(arc)
        }) { Text(stringResource(MR.strings.action_save)) }
        Text(stringResource(MR.strings.translation_story_recent), style = MaterialTheme.typography.titleSmall)
        if (ctx.chapters.isEmpty()) Empty()
        ctx.chapters.sortedByDescending { it.n }.forEach {
            Text("${it.n.label()}. ${it.summary}", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onQuery,
        placeholder = { Text(stringResource(MR.strings.translation_search)) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(16.dp),
    )
}

@Composable
private fun Empty() {
    Text(
        stringResource(MR.strings.translation_context_empty),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun TermDialog(
    entry: GlossaryEntry,
    isNew: Boolean,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (GlossaryEntry) -> Unit,
) {
    var en by remember { mutableStateOf(entry.en) }
    var it by remember { mutableStateOf(entry.it) }
    var keep by remember { mutableStateOf(entry.keep) }
    var locked by remember { mutableStateOf(entry.locked) }
    var kind by remember { mutableStateOf(entry.kind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = en,
                    onValueChange = { en = it },
                    enabled = isNew,
                    label = { Text(stringResource(MR.strings.translation_field_english)) },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = it,
                    onValueChange = { value ->
                        it = value
                        // A correction by hand should stick
                        locked = true
                    },
                    enabled = !keep,
                    label = { Text(stringResource(MR.strings.translation_field_italian)) },
                    singleLine = true,
                )
                Text(stringResource(MR.strings.translation_field_kind), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    KINDS.forEach { k -> FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(k) }) }
                }
                SwitchRow(stringResource(MR.strings.translation_field_keep), keep) { keep = it }
                SwitchRow(stringResource(MR.strings.translation_field_locked), locked) { locked = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = en.isNotBlank() && (keep || it.isNotBlank()),
                onClick = {
                    onSave(
                        entry.copy(
                            en = en.trim(),
                            it = if (keep) en.trim() else it.trim(),
                            keep = keep,
                            locked = locked,
                            kind = kind,
                        ),
                    )
                },
            ) { Text(stringResource(MR.strings.action_save)) }
        },
        dismissButton = {
            if (!isNew) {
                TextButton(onClick = onDelete) {
                    Text(stringResource(MR.strings.action_delete), color = MaterialTheme.colorScheme.error)
                }
            }
        },
    )
}

@Composable
private fun CharacterDialog(
    character: Character,
    others: List<Character>,
    onDismiss: () -> Unit,
    onDelete: () -> Unit,
    onSave: (Character) -> Unit,
    onMerge: (Character) -> Unit,
) {
    var name by remember { mutableStateOf(character.name) }
    var gender by remember { mutableStateOf(character.gender ?: "unknown") }
    var role by remember { mutableStateOf(character.role.orEmpty()) }
    var speech by remember { mutableStateOf(character.speech.orEmpty()) }
    var addresses by remember { mutableStateOf(character.addresses.joinToString("\n") { "${it.to}: ${it.form}" }) }
    // Saving a character by hand locks it, unless the switch is turned off
    var locked by remember { mutableStateOf(true) }
    var mergeMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(MR.strings.translation_field_name)) },
                    singleLine = true,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "m" to MR.strings.translation_gender_m,
                        "f" to MR.strings.translation_gender_f,
                        "unknown" to MR.strings.translation_gender_unknown,
                    ).forEach { (value, label) ->
                        FilterChip(selected = gender == value, onClick = {
                            gender = value
                        }, label = { Text(stringResource(label)) })
                    }
                }
                OutlinedTextField(
                    value = role,
                    onValueChange = { role = it },
                    label = { Text(stringResource(MR.strings.translation_field_role)) },
                )
                OutlinedTextField(
                    value = speech,
                    onValueChange = { speech = it },
                    label = { Text(stringResource(MR.strings.translation_field_speech)) },
                )
                OutlinedTextField(
                    value = addresses,
                    onValueChange = { addresses = it },
                    label = { Text(stringResource(MR.strings.translation_field_addresses)) },
                    minLines = 2,
                )
                SwitchRow(stringResource(MR.strings.translation_field_locked), locked) { locked = it }
                if (others.isNotEmpty()) {
                    Column {
                        TextButton(onClick = {
                            mergeMenu = true
                        }) { Text(stringResource(MR.strings.translation_merge_into)) }
                        DropdownMenu(expanded = mergeMenu, onDismissRequest = { mergeMenu = false }) {
                            others.sortedBy { it.name.lowercase() }.forEach { other ->
                                DropdownMenuItem(text = { Text(other.name) }, onClick = { onMerge(other) })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    val parsed = addresses.lines().mapNotNull { line ->
                        val to = line.substringBeforeLast(':').trim()
                        val form = line.substringAfterLast(':', "").trim().lowercase()
                        if (to.isEmpty() || form !in FORMS) null else Address(to, form)
                    }
                    onSave(
                        character.copy(
                            name = name.trim(),
                            gender = gender,
                            role = role.trim().ifEmpty { null },
                            speech = speech.trim().ifEmpty { null },
                            addresses = parsed,
                            locked = locked,
                        ),
                    )
                },
            ) { Text(stringResource(MR.strings.action_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDelete) {
                Text(stringResource(MR.strings.action_delete), color = MaterialTheme.colorScheme.error)
            }
        },
    )
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val KINDS = listOf("person", "place", "organization", "title", "skill", "item", "creature", "term")
private val FORMS = setOf("tu", "lei", "voi")
