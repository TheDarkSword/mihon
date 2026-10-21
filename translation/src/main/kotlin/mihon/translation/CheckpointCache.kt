package mihon.translation

import kotlinx.serialization.KSerializer
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Per-chapter results of each pipeline stage, so a failure never throws away the work already done. Entries
 * expire after [TTL_DAYS], are deleted when the chapter completes and the whole cache can be cleared.
 */
class CheckpointCache(private val root: File) {

    inner class Entry(key: String) {
        private val dir = File(root, hash(key))

        fun <T> load(stage: String, serializer: KSerializer<T>): T? = File(dir, "$stage.json")
            .takeIf { it.exists() }
            ?.let { runCatching { TranslationPipeline.json.decodeFromString(serializer, it.readText()) }.getOrNull() }

        fun <T> save(stage: String, serializer: KSerializer<T>, value: T) {
            dir.mkdirs()
            val tmp = File(dir, "$stage.json.tmp")
            tmp.writeText(TranslationPipeline.json.encodeToString(serializer, value))
            tmp.renameTo(File(dir, "$stage.json"))
            dir.setLastModified(System.currentTimeMillis())
        }

        fun delete() {
            dir.deleteRecursively()
        }
    }

    fun entry(key: String) = Entry(key)

    fun purgeExpired() {
        val limit = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(TTL_DAYS)
        root.listFiles()?.filter { it.lastModified() < limit }?.forEach { it.deleteRecursively() }
    }

    fun sizeBytes(): Long = root.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun clear() {
        root.deleteRecursively()
    }

    private fun hash(key: String): String = MessageDigest.getInstance("SHA-1")
        .digest(key.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        const val TTL_DAYS = 7L
    }
}
