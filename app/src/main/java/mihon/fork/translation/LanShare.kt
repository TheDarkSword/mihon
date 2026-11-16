package mihon.fork.translation

import android.content.Context
import android.os.Build
import com.hippo.unifile.UniFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import logcat.LogPriority
import logcat.asLog
import logcat.logcat
import mihon.translation.TranslationPipeline
import mihon.translation.context.SeriesContext
import tachiyomi.domain.manga.model.Manga
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.time.Duration.Companion.minutes

/**
 * Sends translated chapters straight to another phone with Mihon IT on the same network.
 *
 * The receiving phone listens while its Receive screen is open. The sender offers a series and its chapters, the
 * receiver accepts and says which chapters it wants (keeping the ones it already has, if it chose so), then the
 * chapters follow one by one and the series memory is merged at the end.
 */
object LanShare {
    const val SERVICE_TYPE = "_mihonit-share._tcp"
    private const val VERSION = 1
    private const val MAX_FRAME = 16 * 1024 * 1024
    private const val MAX_CHAPTER = 500L * 1024 * 1024
    private const val CONNECT_MILLIS = 5_000
    private const val IO_MILLIS = 60_000
    private val ANSWER_TIMEOUT = 3.minutes
    private val CHAPTER_NAME = Regex("""^\d+(\.\d+)?\.cbz$""")

    @Serializable
    data class Chapter(val name: String, val size: Long)

    @Serializable
    data class Offer(
        val version: Int = VERSION,
        val device: String,
        val link: TranslationStorage.SeriesLink,
        val chapters: List<Chapter>,
        val context: SeriesContext? = null,
    )

    @Serializable
    data class Answer(val accept: Boolean, val want: List<String> = emptyList())

    @Serializable
    data class Done(val received: Int)

    val deviceName: String
        get() = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()

    // Sending

    sealed interface Sending {
        data object Connecting : Sending
        data object WaitingForAnswer : Sending
        data class Chapter(val name: String, val index: Int, val total: Int, val fraction: Float) : Sending
    }

    /** Returns how many chapters the other phone stored, or null when it declined. */
    suspend fun send(
        storage: TranslationStorage,
        manga: Manga,
        names: List<String>,
        address: String,
        progress: (Sending) -> Unit,
    ): Int? = withContext(Dispatchers.IO) {
        val dir = storage.seriesDir(manga) ?: throw IOException("No translations for this series")
        val files = names.mapNotNull { name -> dir.findFile(name)?.let { name to it } }
        val host = address.substringBeforeLast(':')
        val port = address.substringAfterLast(':').toIntOrNull() ?: throw IOException("Address without a port")
        progress(Sending.Connecting)
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_MILLIS)
            socket.soTimeout = ANSWER_TIMEOUT.inWholeMilliseconds.toInt() + IO_MILLIS
            val output = DataOutputStream(socket.getOutputStream().buffered())
            val input = DataInputStream(socket.getInputStream().buffered())
            val offer = Offer(
                device = deviceName,
                link = TranslationStorage.SeriesLink(manga.source, manga.url, manga.title),
                chapters = files.map { (name, file) -> Chapter(name, file.length()) },
                context = storage.readContext(manga),
            )
            output.writeFrame(TranslationPipeline.json.encodeToString(Offer.serializer(), offer))
            progress(Sending.WaitingForAnswer)
            val answer = TranslationPipeline.json.decodeFromString(Answer.serializer(), input.readFrame())
            if (!answer.accept) return@withContext null
            socket.soTimeout = IO_MILLIS
            val wanted = files.filter { it.first in answer.want }
            wanted.forEachIndexed { index, (name, file) ->
                val size = file.length()
                output.writeFrame(TranslationPipeline.json.encodeToString(Chapter.serializer(), Chapter(name, size)))
                file.openInputStream().use { stream ->
                    copy(stream, output, size) { done ->
                        progress(
                            Sending.Chapter(
                                name.removeSuffix(".cbz"),
                                index + 1,
                                wanted.size,
                                done / size.toFloat(),
                            ),
                        )
                    }
                }
            }
            output.writeFrame(TranslationPipeline.json.encodeToString(Chapter.serializer(), Chapter("", -1)))
            output.flush()
            TranslationPipeline.json.decodeFromString(Done.serializer(), input.readFrame()).received
        }
    }

    // Receiving

    data class Incoming(val offer: Offer, val conflicts: List<String>, val reply: CompletableDeferred<Answer>)

    sealed interface Receiving {
        data object Waiting : Receiving
        data class Asking(val incoming: Incoming) : Receiving
        data class Chapter(val title: String, val name: String, val index: Int, val total: Int, val fraction: Float) :
            Receiving
        data class Done(val title: String, val count: Int) : Receiving
        data class Failed(val message: String) : Receiving
    }

    /** Listens for one sender at a time while the Receive screen is open. */
    class Receiver(private val context: Context, private val storage: TranslationStorage) {
        private val _state = MutableStateFlow<Receiving>(Receiving.Waiting)
        val state: StateFlow<Receiving> = _state.asStateFlow()

        private var server: ServerSocket? = null
        private var unregister: () -> Unit = {}
        private var job: Job? = null

        val port get() = server?.localPort ?: 0

        fun start(scope: CoroutineScope) {
            if (server != null) return
            val socket = ServerSocket(0)
            server = socket
            unregister = LanDiscovery.register(context, SERVICE_TYPE, deviceName, socket.localPort)
            job = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    val client = try {
                        socket.accept()
                    } catch (e: IOException) {
                        break
                    }
                    client.use { handle(it) }
                }
            }
        }

        fun stop() {
            unregister()
            unregister = {}
            server?.close()
            server = null
            job?.cancel()
            job = null
        }

        /** Back to waiting after the reader saw the outcome of a transfer. */
        fun reset() {
            if (_state.value is Receiving.Done || _state.value is Receiving.Failed) _state.value = Receiving.Waiting
        }

        private suspend fun handle(socket: Socket) {
            var partial: UniFile? = null
            try {
                socket.soTimeout = IO_MILLIS
                val input = DataInputStream(socket.getInputStream().buffered())
                val output = DataOutputStream(socket.getOutputStream().buffered())
                val offer = TranslationPipeline.json.decodeFromString(Offer.serializer(), input.readFrame())
                if (offer.version != VERSION ||
                    offer.chapters.any { !CHAPTER_NAME.matches(it.name) || it.size !in 1..MAX_CHAPTER }
                ) {
                    throw IOException("Unsupported transfer")
                }
                val existing = storage.seriesDir(offer.link)?.listFiles()?.mapNotNull { it.name }.orEmpty().toSet()
                val conflicts = offer.chapters.map { it.name }.filter { it in existing }
                val reply = CompletableDeferred<Answer>()
                _state.value = Receiving.Asking(Incoming(offer, conflicts, reply))
                val answer = withTimeoutOrNull(ANSWER_TIMEOUT) { reply.await() } ?: Answer(false)
                output.writeFrame(TranslationPipeline.json.encodeToString(Answer.serializer(), answer))
                if (!answer.accept) {
                    _state.value = Receiving.Waiting
                    return
                }
                val dir = storage.seriesDir(offer.link, create = true) ?: throw IOException("No storage folder")
                val wanted = answer.want.toSet()
                var received = 0
                while (true) {
                    val header = TranslationPipeline.json.decodeFromString(Chapter.serializer(), input.readFrame())
                    if (header.size < 0) break
                    if (header.name !in wanted || !CHAPTER_NAME.matches(header.name) || header.size > MAX_CHAPTER) {
                        throw IOException("Unexpected chapter ${header.name}")
                    }
                    val label = header.name.removeSuffix(".cbz")
                    dir.findFile(header.name + PART)?.delete()
                    val part = dir.createFile(header.name + PART) ?: throw IOException("Cannot write ${header.name}")
                    partial = part
                    part.openOutputStream().use { out ->
                        copy(input, out, header.size) { done ->
                            _state.value = Receiving.Chapter(
                                offer.link.title,
                                label,
                                received + 1,
                                wanted.size,
                                done / header.size.toFloat(),
                            )
                        }
                    }
                    dir.findFile(header.name)?.delete()
                    if (!part.renameTo(header.name)) throw IOException("Cannot save ${header.name}")
                    partial = null
                    received++
                }
                offer.context?.let { incoming ->
                    val current = storage.readContext(dir)
                    storage.writeContext(
                        dir,
                        if (current ==
                            null
                        ) {
                            incoming
                        } else {
                            TranslationTransfer.merge(current, incoming)
                        },
                    )
                }
                TranslatedChapters.clear()
                output.writeFrame(TranslationPipeline.json.encodeToString(Done.serializer(), Done(received)))
                output.flush()
                _state.value = Receiving.Done(offer.link.title, received)
            } catch (e: Exception) {
                logcat(LogPriority.WARN, TAG) { e.asLog() }
                partial?.delete()
                if (_state.value !is Receiving.Done) {
                    _state.value =
                        Receiving.Failed(e.message ?: e::class.simpleName.orEmpty())
                }
            }
        }
    }

    private const val PART = ".part"
    private const val TAG = "MihonTranslation"

    private fun DataOutputStream.writeFrame(text: String) {
        val bytes = text.toByteArray()
        writeInt(bytes.size)
        write(bytes)
        flush()
    }

    private fun DataInputStream.readFrame(): String {
        val size = readInt()
        if (size !in 0..MAX_FRAME) throw IOException("Bad frame")
        val bytes = ByteArray(size)
        readFully(bytes)
        return bytes.decodeToString()
    }

    /** Copies exactly [size] bytes, reporting progress about every megabyte. */
    private fun copy(input: InputStream, output: OutputStream, size: Long, onProgress: (Long) -> Unit) {
        val buffer = ByteArray(64 * 1024)
        var done = 0L
        var reported = 0L
        while (done < size) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), size - done).toInt())
            if (read < 0) throw EOFException("Transfer interrupted")
            output.write(buffer, 0, read)
            done += read
            if (done - reported >= 1024 * 1024 || done == size) {
                reported = done
                onProgress(done)
            }
        }
        output.flush()
    }
}
