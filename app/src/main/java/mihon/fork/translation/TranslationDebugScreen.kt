package mihon.fork.translation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.launch
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import mihon.translation.ApiKeyStore
import mihon.translation.TranslationPipeline
import mihon.translation.context.SeriesContext
import tachiyomi.presentation.core.components.material.Scaffold
import java.io.File

/**
 * Developer screen to run the translation pipeline on test chapters pushed to
 * the app files dir `translation-test/` (folders of images or CBZ files).
 */
class TranslationDebugScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        val keys = remember { ApiKeyStore(context) }
        val testDir = remember { File(context.filesDir, "translation-test").apply { mkdirs() } }
        val outDir = remember { File(context.filesDir, "translation-out").apply { mkdirs() } }
        var key by remember { mutableStateOf(keys.get(ApiKeyStore.GEMINI).orEmpty()) }
        var running by remember { mutableStateOf(false) }
        var reuse by remember { mutableStateOf(true) }
        val log = remember { mutableStateListOf<String>() }
        val chapters = remember {
            testDir.listFiles().orEmpty().filter { it.isDirectory || it.extension == "cbz" }.sortedBy { it.name }
        }

        Scaffold(
            topBar = { AppBar(title = "Translation test", navigateUp = navigator::pop, scrollBehavior = it) },
        ) { padding ->
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
                item {
                    Column {
                        OutlinedTextField(
                            value = key,
                            onValueChange = { key = it },
                            label = { Text("Gemini API key") },
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(onClick = { keys.set(ApiKeyStore.GEMINI, key) }) { Text("Save key") }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = reuse, onCheckedChange = { reuse = it })
                            Text("Keep checkpoints after success")
                        }
                        Row {
                            Button(
                                enabled = !running && chapters.isNotEmpty(),
                                onClick = {
                                    running = true
                                    log.clear()
                                    scope.launch {
                                        benchmark(context, chapters.first(), keys) { log += it }
                                        running = false
                                    }
                                },
                            ) { Text("Benchmark detector") }
                            Button(
                                enabled = !running,
                                onClick = {
                                    val pipeline = TranslationPipeline(context, apiKey = { null })
                                    log += "Cleared ${pipeline.cache.sizeBytes() / 1024} KB of checkpoints"
                                    pipeline.cache.clear()
                                },
                            ) { Text("Clear cache") }
                        }
                        Text("Chapters in ${testDir.path}", style = MaterialTheme.typography.titleSmall)
                    }
                }
                items(chapters) { chapter ->
                    Text(
                        text = chapter.name,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = !running) {
                                running = true
                                log.clear()
                                scope.launch {
                                    runChapter(context, chapter, outDir, keys, reuse) {
                                        log += it
                                        logcat(LogPriority.INFO, TranslationPipeline.TAG) { it }
                                    }
                                    running = false
                                }
                            }
                            .padding(vertical = 12.dp),
                    )
                    HorizontalDivider()
                }
                items(log) { line ->
                    Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private suspend fun runChapter(
        context: android.content.Context,
        chapter: File,
        outDir: File,
        keys: ApiKeyStore,
        reuse: Boolean,
        log: (String) -> Unit,
    ) {
        val number = Regex("""\d+(\.\d+)?""").find(chapter.nameWithoutExtension)?.value?.toFloat() ?: 0f
        val ctxFile = File(chapter.parentFile, "context.json")
        val ctx = if (ctxFile.exists()) {
            TranslationPipeline.json.decodeFromString<SeriesContext>(ctxFile.readText())
        } else {
            SeriesContext(series = "Return of The Unrivaled Spear Knight")
        }
        try {
            val pipeline = TranslationPipeline(context, apiKey = { keys.get(ApiKeyStore.GEMINI) })
            val (updated, t) = pipeline.run(
                key = "debug:${chapter.name}",
                input = chapter,
                output = File(outDir, "${chapter.nameWithoutExtension}.cbz"),
                ctx = ctx,
                chapter = number,
                keepCheckpoints = reuse,
                progress = log,
            )
            if (t.translation.reviewError ==
                null
            ) {
                ctxFile.writeText(TranslationPipeline.json.encodeToString(SeriesContext.serializer(), updated))
            }
            val s = t.translation
            log("Done in ${t.totalMillis / 1000.0}s, resumed from ${t.resumedFrom ?: "start"}")
            log("pages ${t.pages}, strip ${t.stripHeight}px, regions ${t.detections}, lines ${t.blocks}")
            log("detect ${t.detectMillis / 1000.0}s, ocr ${t.ocrMillis / 1000.0}s, render ${t.renderMillis / 1000.0}s")
            log("translate ${s.translateMillis / 1000.0}s (${s.inputTokens} in / ${s.outputTokens} out)")
            log("review ${s.reviewMillis / 1000.0}s, ${s.reviewFixes} fixes ${s.reviewError.orEmpty()}")
            log("names fixed ${s.namesFixed}, glitches ${s.glitches}, skipped ${s.skipped}")
        } catch (e: Exception) {
            logcat(LogPriority.ERROR, TranslationPipeline.TAG) { e.asLog() }
            log("Error: ${e::class.simpleName}: ${e.message}")
        }
    }

    private suspend fun benchmark(
        context: android.content.Context,
        chapter: File,
        keys: ApiKeyStore,
        log: (String) -> Unit,
    ) {
        val pipeline = TranslationPipeline(context, apiKey = { keys.get(ApiKeyStore.GEMINI) })
        val configs = listOf(
            Triple(4, false, 0.25f),
            Triple(6, false, 0.25f),
            Triple(8, false, 0.25f),
            Triple(6, true, 0.25f),
            Triple(6, false, 0.15f),
        )
        for ((threads, xnnpack, overlap) in configs) {
            val line = try {
                pipeline.benchmarkDetector(chapter, threads, xnnpack, overlap)
            } catch (e: Exception) {
                "threads=$threads xnnpack=$xnnpack: ${e::class.simpleName}: ${e.message}"
            }
            log(line)
            logcat(LogPriority.INFO, TranslationPipeline.TAG) { line }
        }
        log("Benchmark done")
        logcat(LogPriority.INFO, TranslationPipeline.TAG) { "Benchmark done" }
    }
}
