package mihon.fork.translation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.metro.metroGraph
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

/** Sends translated chapters of [manga] to a phone in Receive mode. [preselected] are chapter file names. */
@Composable
fun ShareTranslationsDialog(manga: Manga, preselected: Set<String>?, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val storage = remember { context.metroGraph<TranslationGraph>().translationStorage }
    var chapters by remember { mutableStateOf<List<String>>(emptyList()) }
    var selected by remember { mutableStateOf(preselected.orEmpty()) }
    var found by remember { mutableStateOf<List<LanDiscovery.Found>?>(null) }
    var search by remember { mutableIntStateOf(0) }
    var address by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf<LanShare.Sending?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(manga.id) {
        val names = withContext(Dispatchers.IO) { storage.translated(manga) }
        chapters = names.sortedBy { number(it) }
        if (preselected == null) selected = names
    }
    LaunchedEffect(search) {
        found = null
        val list = LanDiscovery.discover(context, LanShare.SERVICE_TYPE)
        found = list
        if (list.size == 1 && address.isBlank()) address = list[0].address
    }

    fun send() {
        val names = chapters.filter { it in selected }
        scope.launch {
            error = null
            try {
                val count = LanShare.send(storage, manga, names, address.trim()) { sending = it }
                context.toast(
                    if (count == null) {
                        context.getString(tachiyomi.i18n.R.string.translation_share_rejected)
                    } else {
                        context.getString(tachiyomi.i18n.R.string.translation_share_sent, count)
                    },
                )
                onDismiss()
            } catch (e: Exception) {
                error = context.getString(tachiyomi.i18n.R.string.translation_share_error, e.message ?: "")
            }
            sending = null
        }
    }

    AlertDialog(
        // A transfer in progress is not interrupted by a tap outside
        onDismissRequest = { if (sending == null) onDismiss() },
        title = { Text(stringResource(MR.strings.translation_share)) },
        text = {
            val progress = sending
            if (progress != null) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (progress) {
                        LanShare.Sending.Connecting, LanShare.Sending.WaitingForAnswer -> {
                            Text(stringResource(MR.strings.translation_share_waiting))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                        is LanShare.Sending.Chapter -> {
                            Text(
                                stringResource(
                                    MR.strings.translation_share_progress,
                                    progress.name,
                                    progress.index,
                                    progress.total,
                                ),
                            )
                            LinearProgressIndicator(
                                progress = { (progress.index - 1 + progress.fraction) / progress.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            } else {
                ShareChoices(
                    chapters = chapters,
                    selected = selected,
                    onSelected = { selected = it },
                    found = found,
                    onSearchAgain = { search++ },
                    address = address,
                    onAddress = { address = it },
                    error = error,
                )
            }
        },
        confirmButton = {
            if (sending == null) {
                TextButton(
                    enabled = selected.isNotEmpty() && ':' in address,
                    onClick = ::send,
                ) { Text(stringResource(MR.strings.translation_share_send)) }
            }
        },
        dismissButton = {
            if (sending == null) {
                TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) }
            }
        },
    )
}

@Composable
private fun ShareChoices(
    chapters: List<String>,
    selected: Set<String>,
    onSelected: (Set<String>) -> Unit,
    found: List<LanDiscovery.Found>?,
    onSearchAgain: () -> Unit,
    address: String,
    onAddress: (String) -> Unit,
    error: String?,
) {
    var from by remember { mutableStateOf("") }
    var to by remember { mutableStateOf("") }
    Column(
        modifier = Modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            stringResource(MR.strings.translation_share_chapters, selected.size, chapters.size),
            style = MaterialTheme.typography.labelLarge,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = chapters.isNotEmpty() && selected.size == chapters.size,
                onClick = { onSelected(chapters.toSet()) },
                label = { Text(stringResource(MR.strings.translation_share_all)) },
            )
            FilterChip(
                selected = selected.isEmpty(),
                onClick = { onSelected(emptySet()) },
                label = { Text(stringResource(MR.strings.translation_share_none)) },
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        }
        TextButton(
            enabled = from.toDoubleOrNull() != null && to.toDoubleOrNull() != null,
            onClick = {
                val range = from.toDouble()..to.toDouble()
                onSelected(chapters.filter { number(it) in range }.toSet())
            },
        ) { Text(stringResource(MR.strings.translation_share_range)) }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp)) {
            items(chapters, key = { it }) { name ->
                val checked = name in selected
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clickable {
                        onSelected(if (checked) selected - name else selected + name)
                    },
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { onSelected(if (it) selected + name else selected - name) },
                    )
                    Text(stringResource(MR.strings.translation_chapter, name.removeSuffix(".cbz")))
                }
            }
        }

        Text(stringResource(MR.strings.translation_share_receiver), style = MaterialTheme.typography.labelLarge)
        when (val list = found) {
            null -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(MR.strings.translation_share_searching),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            else -> {
                if (list.isEmpty()) Text(stringResource(MR.strings.translation_share_none_found))
                list.forEach { item ->
                    FilterChip(
                        selected = address == item.address,
                        onClick = { onAddress(item.address) },
                        label = { Text(item.name) },
                    )
                }
                TextButton(onClick = onSearchAgain) {
                    Text(stringResource(MR.strings.translation_pc_search_again))
                }
            }
        }
        OutlinedTextField(
            value = address,
            onValueChange = { onAddress(it.trim()) },
            label = { Text(stringResource(MR.strings.translation_share_address)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
}

private fun number(name: String) = name.removeSuffix(".cbz").toDoubleOrNull() ?: Double.MAX_VALUE

/** Makes this phone visible on the network and receives translations while it is open. */
class ReceiveTranslationsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val storage = remember { context.metroGraph<TranslationGraph>().translationStorage }
        val receiver = remember { LanShare.Receiver(context.applicationContext, storage) }
        val state by receiver.state.collectAsState()
        var port by remember { mutableIntStateOf(0) }
        var conflicts by remember { mutableStateOf<LanShare.Incoming?>(null) }

        DisposableEffect(receiver) {
            receiver.start(scope)
            port = receiver.port
            onDispose { receiver.stop() }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.pref_translation_receive),
                    navigateUp = navigator::pop,
                    scrollBehavior = it,
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val address = LanDiscovery.localAddress(context)?.let { "$it:$port" } ?: "-"
                Text(stringResource(MR.strings.translation_receive_visible, LanShare.deviceName, address))
                when (val s = state) {
                    LanShare.Receiving.Waiting, is LanShare.Receiving.Asking -> {
                        Text(stringResource(MR.strings.translation_receive_waiting))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    is LanShare.Receiving.Chapter -> {
                        Text(stringResource(MR.strings.translation_receive_progress, s.name, s.index, s.total))
                        LinearProgressIndicator(
                            progress = { (s.index - 1 + s.fraction) / s.total },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    is LanShare.Receiving.Done -> {
                        Text(stringResource(MR.strings.translation_receive_done, s.count, s.title))
                        TextButton(onClick = receiver::reset) { Text(stringResource(MR.strings.action_ok)) }
                    }
                    is LanShare.Receiving.Failed -> {
                        Text(
                            stringResource(MR.strings.translation_share_error, s.message),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = receiver::reset) { Text(stringResource(MR.strings.action_ok)) }
                    }
                }
            }
        }

        val asking = (state as? LanShare.Receiving.Asking)?.incoming
        if (asking != null && conflicts == null) {
            val offer = asking.offer
            AlertDialog(
                onDismissRequest = {},
                text = {
                    Text(
                        stringResource(
                            MR.strings.translation_receive_offer,
                            offer.device,
                            offer.chapters.size,
                            offer.link.title,
                        ),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            if (asking.conflicts.isEmpty()) {
                                asking.reply.complete(LanShare.Answer(true, offer.chapters.map { it.name }))
                            } else {
                                conflicts = asking
                            }
                        },
                    ) { Text(stringResource(MR.strings.translation_receive_accept)) }
                },
                dismissButton = {
                    TextButton(onClick = { asking.reply.complete(LanShare.Answer(false)) }) {
                        Text(stringResource(MR.strings.translation_receive_decline))
                    }
                },
            )
        }
        conflicts?.let { incoming ->
            ChapterConflictsDialog(
                title = incoming.offer.link.title,
                confirm = stringResource(MR.strings.translation_receive_accept),
                conflicts = incoming.conflicts.sortedBy { number(it) },
                onDismiss = {
                    conflicts = null
                    incoming.reply.complete(LanShare.Answer(false))
                },
                onConfirm = { overwrite ->
                    conflicts = null
                    val keep = incoming.conflicts.toSet() - overwrite
                    incoming.reply.complete(
                        LanShare.Answer(true, incoming.offer.chapters.map { it.name }.filter { it !in keep }),
                    )
                },
            )
        }
    }
}
