import java.net.URI
import java.security.MessageDigest

plugins {
    alias(mihonx.plugins.android.library)
    alias(mihonx.plugins.spotless)

    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "mihon.translation"

    androidResources {
        noCompress += "onnx"
    }
}

/** Fetches the ONNX models at build time instead of keeping ~40 MB of binaries in git. */
@CacheableTask
abstract class DownloadTranslationModels : DefaultTask() {
    /** File name to "url sha256". */
    @get:Input
    abstract val models: MapProperty<String, String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun download() {
        val dir = outputDir.get().asFile.resolve("translation").apply { mkdirs() }
        models.get().forEach { (name, spec) ->
            val (url, sha256) = spec.split(" ")
            val file = dir.resolve(name)
            if (file.exists() && digest(file.readBytes()) == sha256) return@forEach
            URI(url).toURL().openStream().use { input -> file.outputStream().use { input.copyTo(it) } }
            check(digest(file.readBytes()) == sha256) { "Checksum mismatch for $name" }
        }
    }

    private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
        .joinToString("") { "%02x".format(it) }
}

val downloadModels = tasks.register<DownloadTranslationModels>("downloadTranslationModels") {
    models.put(
        "detector.onnx",
        "https://huggingface.co/ogkalu/comic-text-and-bubble-detector/resolve/" +
            "16e8a622f91fabc6b5b65c96d32d1183f8843546/detector-v4-s_int8.onnx " +
            "5fe9e4f576e49d4e7e8b0e029d6d3cdc252abd4694113e1cae120e62c931ea79",
    )
    models.put(
        "migan.onnx",
        "https://huggingface.co/andraniksargsyan/migan/resolve/" +
            "406830d0fa60666da0071c342ad2fbc8f30c5c64/migan_pipeline_v2.onnx " +
            "6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b",
    )
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadModels, DownloadTranslationModels::outputDir)
    }
}

dependencies {
    implementation(projects.core.archive)

    implementation(libs.bundles.kotlinx.coroutines)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp.core)
    implementation(libs.unifile)
    implementation(libs.logcat)
    implementation(libs.androidx.core)

    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.30.0")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
}
