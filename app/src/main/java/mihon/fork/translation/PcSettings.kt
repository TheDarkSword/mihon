package mihon.fork.translation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.metro.metroGraph
import mihon.translation.pc.PcClient
import mihon.translation.pc.PcException
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import kotlin.math.roundToInt

/** Settings of the translator on the PC: pairing, default translator and model, chapters waiting for it. */
@Composable
fun pcTranslatorGroup(): Preference.PreferenceGroup {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val graph = remember { context.metroGraph<TranslationGraph>() }
    val pc = graph.pcTranslator
    val manager = graph.translationManager
    val preferences = graph.translationPreferences
    var paired by remember { mutableStateOf(pc.isPaired()) }
    var pairing by remember { mutableStateOf(false) }
    var managing by remember { mutableStateOf(false) }
    var confirmGemini by remember { mutableStateOf(false) }
    val queue by manager.queue.collectAsState()
    val name by preferences.pcName.collectAsState()
    val address by preferences.pcAddress.collectAsState()
    val waiting = queue.count { it.onPc }

    if (pairing) {
        PcPairDialog(
            onDismiss = { pairing = false },
            onPaired = {
                pairing = false
                paired = true
            },
        )
    }
    if (managing) {
        PcManageDialog(
            onDismiss = { managing = false },
            onUnpaired = {
                managing = false
                paired = false
            },
        )
    }
    if (confirmGemini) {
        AlertDialog(
            onDismissRequest = { confirmGemini = false },
            text = { Text(stringResource(MR.strings.translation_use_gemini_confirm, waiting)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmGemini = false
                        scope.launch(Dispatchers.IO) { moveToGemini(pc, manager, null) }
                    },
                ) { Text(stringResource(MR.strings.translation_use_gemini)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmGemini = false }) { Text(stringResource(MR.strings.action_cancel)) }
            },
        )
    }

    val items = buildList<Preference.PreferenceItem<out Any, out Any>> {
        add(
            Preference.PreferenceItem.TextPreference(
                title = stringResource(MR.strings.pref_translation_pc_device),
                subtitle = if (paired) {
                    stringResource(MR.strings.pref_translation_pc_paired, name, address.removePrefix("http://"))
                } else {
                    stringResource(MR.strings.pref_translation_pc_not_paired)
                },
                onClick = { if (paired) managing = true else pairing = true },
            ),
        )
        if (paired) {
            add(
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.engine,
                    entries = mapOf(
                        TranslationJob.ENGINE_PC to stringResource(MR.strings.pref_translation_pc),
                        TranslationJob.ENGINE_GEMINI to "Gemini",
                    ),
                    title = stringResource(MR.strings.pref_translation_engine),
                ),
            )
            add(
                Preference.PreferenceItem.ListPreference(
                    preference = preferences.pcModel,
                    entries = mapOf(
                        TranslationJob.MODEL_OPUS to stringResource(MR.strings.translation_model_opus),
                        TranslationJob.MODEL_SONNET to stringResource(MR.strings.translation_model_sonnet),
                    ),
                    title = stringResource(MR.strings.pref_translation_pc_model),
                ),
            )
        }
        if (waiting > 0) {
            add(
                Preference.PreferenceItem.TextPreference(
                    title = stringResource(MR.strings.pref_translation_pc_waiting),
                    subtitle = stringResource(MR.strings.pref_translation_pc_waiting_summary, waiting),
                    onClick = { confirmGemini = true },
                ),
            )
        }
    }
    return Preference.PreferenceGroup(title = stringResource(MR.strings.pref_translation_pc), preferenceItems = items)
}

/** Moves the chapters waiting for the PC to Gemini and withdraws the ones already sent. */
suspend fun moveToGemini(pc: PcTranslator, manager: TranslationManager, mangaId: Long?) {
    manager.useGemini(mangaId).forEach { job ->
        val id = job.pcRemoteId ?: return@forEach
        runCatching { pc.withClient { it.cancel(id) } }
        pc.clearPending(job.chapterId)
    }
}

@Composable
private fun PcPairDialog(onDismiss: () -> Unit, onPaired: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pc = remember { context.metroGraph<TranslationGraph>().pcTranslator }
    var found by remember { mutableStateOf<List<PcDiscovery.Found>?>(null) }
    var search by remember { mutableIntStateOf(0) }
    var address by remember { mutableStateOf("") }
    var service by remember { mutableStateOf<String?>(null) }
    var codeRequested by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(search) {
        found = null
        val list = PcDiscovery.discover(context)
        found = list
        if (list.size == 1 && address.isBlank()) {
            address = list[0].address.removePrefix("http://")
            service = list[0].name
        }
    }

    fun run(block: suspend () -> Unit) {
        scope.launch {
            busy = true
            error = null
            try {
                withContext(Dispatchers.IO) { block() }
            } catch (e: PcException) {
                error = context.getString(tachiyomi.i18n.R.string.translation_pc_error, e.message)
            }
            busy = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.translation_pc_pair)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!codeRequested) {
                    when (val list = found) {
                        null -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(
                                stringResource(MR.strings.translation_pc_searching),
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                        else -> {
                            if (list.isEmpty()) Text(stringResource(MR.strings.translation_pc_none_found))
                            list.forEach { item ->
                                val host = item.address.removePrefix("http://")
                                FilterChip(
                                    selected = address == host,
                                    onClick = {
                                        address = host
                                        service = item.name
                                    },
                                    label = { Text("${item.name} · $host") },
                                )
                            }
                            TextButton(onClick = { search++ }) {
                                Text(stringResource(MR.strings.translation_pc_search_again))
                            }
                        }
                    }
                    OutlinedTextField(
                        value = address,
                        onValueChange = {
                            address = it.trim()
                            service = null
                        },
                        label = { Text(stringResource(MR.strings.translation_pc_address)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Text(stringResource(MR.strings.translation_pc_code_hint))
                    OutlinedTextField(
                        value = code,
                        onValueChange = { value -> code = value.filter { it.isDigit() }.take(6) },
                        label = { Text(stringResource(MR.strings.translation_pc_code)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        singleLine = true,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            if (!codeRequested) {
                TextButton(
                    enabled = address.isNotBlank() && !busy,
                    onClick = {
                        run {
                            pc.requestCode(PcClient.address(address))
                            codeRequested = true
                        }
                    },
                ) { Text(stringResource(MR.strings.translation_pc_request_code)) }
            } else {
                TextButton(
                    enabled = code.length == 6 && !busy,
                    onClick = {
                        run {
                            pc.pair(PcClient.address(address), service, code)
                            withContext(Dispatchers.Main) {
                                context.toast(MR.strings.translation_pc_paired_ok)
                                onPaired()
                            }
                        }
                    },
                ) { Text(stringResource(MR.strings.action_ok)) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}

@Composable
private fun PcManageDialog(onDismiss: () -> Unit, onUnpaired: () -> Unit) {
    val context = LocalContext.current
    val pc = remember { context.metroGraph<TranslationGraph>().pcTranslator }
    var check by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(check) {
        status = null
        status = withContext(Dispatchers.IO) {
            try {
                val s = pc.withClient { it.status() }
                context.getString(
                    tachiyomi.i18n.R.string.translation_pc_online,
                    ((s.fiveHour ?: 0.0) * 100).roundToInt(),
                    ((s.week ?: 0.0) * 100).roundToInt(),
                    s.queue.size,
                )
            } catch (e: PcException) {
                context.getString(tachiyomi.i18n.R.string.translation_pc_error, e.message)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.pref_translation_pc)) },
        text = {
            when (val text = status) {
                null -> CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> Text(text)
            }
        },
        confirmButton = {
            TextButton(onClick = { check++ }) { Text(stringResource(MR.strings.translation_pc_check)) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    pc.unpair()
                    onUnpaired()
                },
            ) { Text(stringResource(MR.strings.translation_pc_unpair), color = MaterialTheme.colorScheme.error) }
        },
    )
}
