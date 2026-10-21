package mihon.fork.translation

import android.text.format.Formatter
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
                        preference = preferences.deleteOriginal,
                        title = stringResource(MR.strings.pref_translation_delete_original),
                        subtitle = stringResource(MR.strings.pref_translation_delete_original_summary),
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
