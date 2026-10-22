package mihon.fork.translation

import android.text.format.Formatter
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import eu.kanade.presentation.more.settings.Preference
import eu.kanade.presentation.more.settings.screen.SearchableSettings
import eu.kanade.tachiyomi.util.system.openInBrowser
import eu.kanade.tachiyomi.util.system.toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.metro.metroGraph
import mihon.translation.ApiKeyStore
import mihon.translation.CheckpointCache
import mihon.translation.llm.GeminiClient
import mihon.translation.render.TextStyle
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import java.io.File

object SettingsTranslationScreen : SearchableSettings {

    @ReadOnlyComposable
    @Composable
    override fun getTitleRes() = MR.strings.translation

    @Composable
    override fun getPreferences(): List<Preference> {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val graph = remember { context.metroGraph<TranslationGraph>() }
        val manager = graph.translationManager
        val preferences = graph.translationPreferences
        val keys = manager.keys
        var hasKey by remember { mutableStateOf(manager.hasKey()) }
        var editKey by remember { mutableStateOf(false) }
        val queue by manager.queue.collectAsState()
        val requests by preferences.requestsCount.collectAsState()
        val cache = remember { CheckpointCache(File(context.cacheDir, "translation")) }
        var cacheSize by remember { mutableLongStateOf(cache.sizeBytes()) }
        var confirmDeleteAll by remember { mutableStateOf(false) }
        var qualityWarning by remember { mutableStateOf(false) }
        var importing by remember { mutableStateOf<Pair<android.net.Uri, TranslationTransfer.Preview>?>(null) }
        val transfer = remember { TranslationTransfer(context, graph.translationStorage) }

        fun runImport(uri: android.net.Uri, preview: TranslationTransfer.Preview, overwrite: Set<String>) {
            scope.launch {
                val count = withContext(Dispatchers.IO) { transfer.import(uri, preview, overwrite) }
                context.toast(
                    context.getString(tachiyomi.i18n.R.string.translation_imported, count, preview.link.title),
                )
            }
        }
        val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri ?: return@rememberLauncherForActivityResult
            scope.launch {
                val preview = withContext(Dispatchers.IO) { runCatching { transfer.preview(uri) }.getOrNull() }
                when {
                    preview == null -> context.toast(MR.strings.translation_import_invalid)
                    preview.conflicts.isEmpty() -> runImport(uri, preview, emptySet())
                    else -> importing = uri to preview
                }
            }
        }
        importing?.let { (uri, preview) ->
            ImportConflictsDialog(
                conflicts = preview.conflicts,
                onDismiss = { importing = null },
                onConfirm = { overwrite ->
                    importing = null
                    runImport(uri, preview, overwrite)
                },
            )
        }

        if (qualityWarning) {
            AlertDialog(
                onDismissRequest = { qualityWarning = false },
                title = { Text(stringResource(MR.strings.pref_translation_quality_review)) },
                text = { Text(stringResource(MR.strings.pref_translation_quality_review_warning)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            preferences.qualityReview.set(true)
                            qualityWarning = false
                        },
                    ) { Text(stringResource(MR.strings.translation_enable)) }
                },
                dismissButton = {
                    TextButton(onClick = { qualityWarning = false }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }

        if (confirmDeleteAll) {
            AlertDialog(
                onDismissRequest = { confirmDeleteAll = false },
                text = { Text(stringResource(MR.strings.pref_translation_delete_all_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            confirmDeleteAll = false
                            scope.launch(Dispatchers.IO) { graph.translationStorage.deleteAllChapters() }
                        },
                    ) { Text(stringResource(MR.strings.action_delete)) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        confirmDeleteAll = false
                    }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }

        if (editKey) {
            var value by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { editKey = false },
                title = { Text(stringResource(MR.strings.pref_gemini_key)) },
                text = {
                    OutlinedTextField(
                        value = value,
                        onValueChange = { value = it },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            keys.set(ApiKeyStore.GEMINI, value)
                            hasKey = manager.hasKey()
                            editKey = false
                        },
                    ) { Text(stringResource(MR.strings.action_ok)) }
                },
                dismissButton = {
                    TextButton(onClick = { editKey = false }) { Text(stringResource(MR.strings.action_cancel)) }
                },
            )
        }

        val ok = stringResource(MR.strings.pref_gemini_key_ok)
        return listOf(
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_api),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_gemini_key),
                        subtitle = stringResource(
                            if (hasKey) MR.strings.pref_gemini_key_set else MR.strings.pref_gemini_key_missing,
                        ),
                        onClick = { editKey = true },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_gemini_key_verify),
                        subtitle = stringResource(MR.strings.pref_translation_requests_today, manager.requestsToday())
                            .takeIf { requests >= 0 },
                        onClick = {
                            val key = keys.get(ApiKeyStore.GEMINI)
                            if (key.isNullOrBlank()) {
                                context.toast(MR.strings.translation_missing_key)
                                return@TextPreference
                            }
                            scope.launch {
                                val error = withContext(Dispatchers.IO) { GeminiClient({ key }).verify(key) }
                                context.toast(
                                    error?.let { context.getString(tachiyomi.i18n.R.string.pref_gemini_key_error, it) }
                                        ?: ok,
                                )
                            }
                        },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_gemini_key_get),
                        subtitle = AI_STUDIO,
                        onClick = { context.openInBrowser(AI_STUDIO) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_behaviour),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.qualityReview,
                        title = stringResource(MR.strings.pref_translation_quality_review),
                        subtitle = stringResource(MR.strings.pref_translation_quality_review_summary),
                        // Turning it on goes through the warning, which saves the value itself
                        onValueChanged = { enable ->
                            if (enable) qualityWarning = true
                            !enable
                        },
                    ),
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.deleteOriginal,
                        title = stringResource(MR.strings.pref_translation_delete_original),
                        subtitle = stringResource(MR.strings.pref_translation_delete_original_summary),
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_style),
                preferenceItems = listOf(
                    Preference.PreferenceItem.SwitchPreference(
                        preference = preferences.textUppercase,
                        title = stringResource(MR.strings.pref_translation_text_uppercase),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.textScale,
                        entries = mapOf(
                            85 to stringResource(MR.strings.pref_translation_text_smaller),
                            100 to stringResource(MR.strings.pref_translation_text_normal),
                            115 to stringResource(MR.strings.pref_translation_text_larger),
                            130 to stringResource(MR.strings.pref_translation_text_much_larger),
                        ),
                        title = stringResource(MR.strings.pref_translation_text_size),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.textFont,
                        entries = mapOf(
                            TextStyle.COMIC_NEUE to "Comic Neue",
                            TextStyle.BANGERS to "Bangers",
                            TextStyle.PATRICK_HAND to "Patrick Hand",
                            TextStyle.KALAM to "Kalam",
                        ),
                        title = stringResource(MR.strings.pref_translation_text_font),
                    ),
                    Preference.PreferenceItem.ListPreference(
                        preference = preferences.textWeight,
                        entries = mapOf(
                            50 to stringResource(MR.strings.pref_translation_text_light),
                            100 to stringResource(MR.strings.pref_translation_text_normal),
                            180 to stringResource(MR.strings.pref_translation_text_bold),
                        ),
                        title = stringResource(MR.strings.pref_translation_text_weight),
                    ),
                    Preference.PreferenceItem.InfoPreference(stringResource(MR.strings.pref_translation_style_note)),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_transfer),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_import),
                        subtitle = stringResource(MR.strings.pref_translation_import_summary),
                        onClick = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
                    ),
                ),
            ),
            Preference.PreferenceGroup(
                title = stringResource(MR.strings.pref_translation_queue),
                preferenceItems = listOf(
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_queue_clear),
                        subtitle = stringResource(MR.strings.pref_translation_queue_summary, queue.size),
                        onClick = { manager.clear() },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_delete_all),
                        subtitle = stringResource(MR.strings.pref_translation_delete_all_summary),
                        onClick = { confirmDeleteAll = true },
                    ),
                    Preference.PreferenceItem.TextPreference(
                        title = stringResource(MR.strings.pref_translation_cache),
                        subtitle = stringResource(
                            MR.strings.pref_translation_cache_summary,
                            Formatter.formatShortFileSize(context, cacheSize),
                        ),
                        onClick = {
                            cache.clear()
                            cacheSize = 0
                        },
                    ),
                ),
            ),
        )
    }

    private const val AI_STUDIO = "https://aistudio.google.com/apikey"
}

@Composable
private fun ImportConflictsDialog(conflicts: List<String>, onDismiss: () -> Unit, onConfirm: (Set<String>) -> Unit) {
    var overwrite by remember { mutableStateOf(emptySet<String>()) }
    var applyAll by remember { mutableStateOf(false) }
    fun choose(name: String, replace: Boolean) {
        overwrite = when {
            applyAll -> if (replace) conflicts.toSet() else emptySet()
            replace -> overwrite + name
            else -> overwrite - name
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(MR.strings.pref_translation_import)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(MR.strings.translation_import_conflicts, conflicts.size))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = applyAll, onCheckedChange = { applyAll = it })
                    Text(stringResource(MR.strings.translation_import_apply_all))
                }
                conflicts.forEach { name ->
                    val replace = name in overwrite
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(MR.strings.translation_chapter, name.removeSuffix(".cbz")),
                            modifier = Modifier.weight(1f),
                        )
                        FilterChip(
                            selected = !replace,
                            onClick = { choose(name, false) },
                            label = { Text(stringResource(MR.strings.translation_import_keep)) },
                        )
                        FilterChip(
                            selected = replace,
                            onClick = { choose(name, true) },
                            label = { Text(stringResource(MR.strings.translation_import_overwrite)) },
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(overwrite) }) { Text(stringResource(MR.strings.translation_import_start)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(MR.strings.action_cancel)) } },
    )
}
