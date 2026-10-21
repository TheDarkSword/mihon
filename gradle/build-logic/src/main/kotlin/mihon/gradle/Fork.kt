package mihon.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Project

/**
 * Moves every variant to its own application id so it can be installed next to upstream,
 * and appends the fork build number (`-Pfork.build=N`) to version name and code.
 */
object Fork {
    private const val UPSTREAM_APPLICATION_ID = "app.mihon"
    private const val APPLICATION_ID = "app.mihon.it"

    // Leaves room for fork builds between two upstream version codes
    private const val VERSION_CODE_MULTIPLIER = 1000

    fun configure(project: Project) {
        val buildNumber = project.providers.gradleProperty("fork.build").orNull?.toInt() ?: 0
        require(buildNumber in 0..<VERSION_CODE_MULTIPLIER) {
            "fork.build must be between 0 and ${VERSION_CODE_MULTIPLIER - 1}"
        }

        project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java).onVariants { variant ->
            val applicationId = variant.applicationId.get()
            variant.applicationId.set(applicationId.replaceFirst(UPSTREAM_APPLICATION_ID, APPLICATION_ID))

            variant.outputs.forEach { output ->
                val versionCode = output.versionCode.orNull ?: return@forEach
                val versionName = output.versionName.orNull ?: return@forEach
                output.versionCode.set(versionCode * VERSION_CODE_MULTIPLIER + buildNumber)
                output.versionName.set("$versionName-it.$buildNumber")
            }
        }
    }
}
