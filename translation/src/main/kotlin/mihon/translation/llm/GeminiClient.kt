package mihon.translation.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import logcat.LogPriority
import logcat.logcat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class LlmException(val status: Int, message: String) : IOException(message) {
    /** Overload or quota errors: the job can be parked and retried later. */
    val isTemporary get() = status == 429 || status >= 500 || status == 0
}

data class LlmResult(
    val json: JsonObject,
    val inputTokens: Int,
    val outputTokens: Int,
    val millis: Long,
)

/** Gemini REST client returning schema constrained JSON. Fails fast: at most [ATTEMPTS] tries. */
class GeminiClient(
    private val apiKey: () -> String?,
    private val onCall: (model: String, status: Int) -> Unit = { _, _ -> },
) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    suspend fun generate(
        model: String,
        system: String,
        user: String,
        schema: JsonElement,
        temperature: Double = 0.4,
        images: List<Pair<String, ByteArray>> = emptyList(),
        attempts: Int = ATTEMPTS,
        timeoutSeconds: Long = 180,
    ): LlmResult = withContext(Dispatchers.IO) {
        val call = http.newBuilder().readTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        val key = apiKey() ?: throw LlmException(401, "Missing Gemini API key")
        val body = buildJsonObject {
            putJsonObject("systemInstruction") {
                put("parts", buildJsonArray { add(buildJsonObject { put("text", system) }) })
            }
            put(
                "contents",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("role", "user")
                            put(
                                "parts",
                                buildJsonArray {
                                    add(buildJsonObject { put("text", user) })
                                    images.forEach { (label, jpeg) ->
                                        add(buildJsonObject { put("text", label) })
                                        add(
                                            buildJsonObject {
                                                putJsonObject("inline_data") {
                                                    put("mime_type", "image/jpeg")
                                                    put("data", Base64.getEncoder().encodeToString(jpeg))
                                                }
                                            },
                                        )
                                    }
                                },
                            )
                        },
                    )
                },
            )
            putJsonObject("generationConfig") {
                put("temperature", temperature)
                put("responseMimeType", "application/json")
                put("responseJsonSchema", schema)
                // Crops of lettering read fine at medium resolution, at a quarter of the tokens
                if (images.isNotEmpty()) put("mediaResolution", "MEDIA_RESOLUTION_MEDIUM")
            }
        }.toString()

        var last: LlmException? = null
        repeat(attempts) { attempt ->
            if (attempt > 0) delay(RETRY_DELAY_MS)
            val start = System.currentTimeMillis()
            val request = Request.Builder()
                .url("$BASE/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val (code, text) = try {
                call.newCall(request).execute().use { it.code to it.body.string() }
            } catch (e: IOException) {
                logcat(LogPriority.WARN, TAG) {
                    "$model attempt ${attempt + 1}: ${e::class.simpleName} after ${elapsed(start)}"
                }
                last = LlmException(0, e.message ?: "Network error")
                return@repeat
            }
            logcat(LogPriority.INFO, TAG) { "$model attempt ${attempt + 1}: HTTP $code after ${elapsed(start)}" }
            onCall(model, code)
            if (code != 200) {
                last = LlmException(code, text.take(300))
                if (!last!!.isTemporary) throw last!!
                return@repeat
            }
            val data = json.parseToJsonElement(text).jsonObject
            val candidate = data["candidates"]!!.jsonArray[0].jsonObject
            val output = candidate["content"]!!.jsonObject["parts"]!!.jsonArray
                .filter { it.jsonObject["thought"] == null }
                .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content.orEmpty() }
            val usage = data["usageMetadata"]?.jsonObject
            return@withContext LlmResult(
                json = json.parseToJsonElement(output).jsonObject,
                inputTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.int ?: 0,
                outputTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.int ?: 0,
                millis = System.currentTimeMillis() - start,
            )
        }
        throw last ?: LlmException(0, "Unknown error")
    }

    /** Null when [key] can list models, otherwise a short error. */
    suspend fun verify(key: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url("$BASE?pageSize=1").header("x-goog-api-key", key).build()
        try {
            http.newCall(request).execute().use { if (it.code == 200) null else "HTTP ${it.code}" }
        } catch (e: IOException) {
            e.message ?: e::class.simpleName
        }
    }

    private fun elapsed(start: Long) = "${(System.currentTimeMillis() - start) / 1000.0}s"

    companion object {
        private const val TAG = "MihonTranslation"
        private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val ATTEMPTS = 2
        private const val RETRY_DELAY_MS = 10_000L

        val json = Json { ignoreUnknownKeys = true }
    }
}
