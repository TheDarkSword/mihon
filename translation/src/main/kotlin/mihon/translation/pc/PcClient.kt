package mihon.translation.pc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.serializer
import mihon.translation.TranslationPipeline
import mihon.translation.context.SeriesContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

class PcException(val status: Int, message: String) : IOException(message) {
    /** The PC did not answer at all: switched off, asleep or on another network. */
    val unreachable get() = status == 0
}

/** Talks to the translator on the reader's PC over the home network. [address] is like `http://192.168.1.10:47321`. */
class PcClient(address: String, private val token: String? = null) {

    private val base: HttpUrl = address.trimEnd('/').toHttpUrl()

    suspend fun info(): PcInfo = call("GET", listOf("info"))

    suspend fun startPairing(device: String) {
        call<PcError>("POST", listOf("pair", "start"), json(PcPairRequest(device)))
    }

    suspend fun confirmPairing(device: String, code: String): String =
        call<PcToken>("POST", listOf("pair", "confirm"), json(PcPairRequest(device, code))).token

    suspend fun status(): PcStatus = call("GET", listOf("status"))

    suspend fun upload(zip: File): PcJob =
        call(
            "POST",
            listOf("jobs"),
            zip.asRequestBody("application/zip".toMediaType()),
            timeoutSeconds = UPLOAD_TIMEOUT,
        )

    suspend fun jobs(): List<PcJob> = call<PcJobs>("GET", listOf("jobs")).jobs

    /**
     * Waits on the PC until a job or its state changes after [cursor] (any value for the first call), at most
     * [seconds]: the PC answers the moment a translation is ready, with nothing lost between two calls.
     */
    suspend fun waitForChange(cursor: Long, seconds: Int = WAIT_SECONDS): PcEvents =
        call(
            "GET",
            listOf("wait"),
            query = mapOf("since" to cursor.toString(), "timeout" to seconds.toString()),
            timeoutSeconds =
            seconds + 20L,
        )

    suspend fun result(id: String): PcResult = call("GET", listOf("jobs", id, "result"))

    suspend fun ack(id: String) {
        call<PcError>("POST", listOf("jobs", id, "ack"), json(PcError()))
    }

    suspend fun cancel(id: String) {
        call<PcJob>("DELETE", listOf("jobs", id))
    }

    suspend fun syncContext(key: String, context: SeriesContext, base: SeriesContext?): PcContextSync =
        call("POST", listOf("series", key, "context"), json(PcSyncRequest(context, base)))

    private inline fun <reified T> json(value: T): RequestBody =
        TranslationPipeline.json.encodeToString(serializer<T>(), value).toRequestBody(JSON)

    private suspend inline fun <reified T> call(
        method: String,
        path: List<String>,
        body: RequestBody? = null,
        query: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = READ_TIMEOUT,
    ): T = execute(method, path, body, query, timeoutSeconds, serializer())

    private suspend fun <T> execute(
        method: String,
        path: List<String>,
        body: RequestBody?,
        query: Map<String, String>,
        timeoutSeconds: Long,
        decoder: KSerializer<T>,
    ): T = withContext(Dispatchers.IO) {
        val url = base.newBuilder().addPathSegment("v1")
            .apply {
                path.forEach { addPathSegment(it) }
                query.forEach { (k, v) -> addQueryParameter(k, v) }
            }
            .build()
        val request = Request.Builder().url(url).method(method, body)
            .apply { token?.let { header("Authorization", "Bearer $it") } }
            .build()
        val client = http.newBuilder().readTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
        val response = try {
            client.newCall(request).execute()
        } catch (e: IOException) {
            throw PcException(0, e.message ?: "PC not reachable")
        }
        response.use {
            val text = it.body.string()
            if (!it.isSuccessful) {
                val message = runCatching { TranslationPipeline.json.decodeFromString<PcError>(text).error }
                    .getOrNull()?.ifBlank { null } ?: "HTTP ${it.code}"
                throw PcException(it.code, message)
            }
            TranslationPipeline.json.decodeFromString(decoder, text)
        }
    }

    companion object {
        const val PORT = 47321
        const val SERVICE_TYPE = "_mihonit._tcp"
        private const val READ_TIMEOUT = 30L
        const val WAIT_SECONDS = 50
        private const val UPLOAD_TIMEOUT = 120L
        private val JSON = "application/json".toMediaType()

        private val http = OkHttpClient.Builder()
            .connectTimeout(4, TimeUnit.SECONDS)
            .writeTimeout(UPLOAD_TIMEOUT, TimeUnit.SECONDS)
            .build()

        fun address(host: String, port: Int = PORT): String =
            if (host.startsWith("http")) host else "http://${if (':' in host) host else "$host:$port"}"
    }
}
