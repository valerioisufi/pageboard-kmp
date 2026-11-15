import com.android.build.api.dsl.ApplicationExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import java.io.File

/**
 * The signing of the release of an Android application with the upload key of its store, read from
 * this machine and never from the repository.
 *
 * The application `<name>-android` reads, in order, `<name>.signing.storeFile`, `.storePassword`,
 * `.keyAlias` and `.keyPassword` in `local.properties` (the file relative to the root of the
 * repository, or absolute), else `<NAME>_SIGNING_STORE_FILE`, `_STORE_PASSWORD`, `_KEY_ALIAS` and
 * `_KEY_PASSWORD` in the environment. With the four of them the release is signed; without them it
 * stays unsigned, so that a machine or a CI without the key still builds it.
 */
class AndroidSigningConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val application = name.removeSuffix("-android")
        pluginManager.withPlugin("com.android.application") {
            val signing = ReleaseSigning.of(this@with, application)
            extensions.configure<ApplicationExtension> {
                if (signing != null) {
                    val release = signingConfigs.create("release") {
                        storeFile = signing.storeFile
                        storePassword = signing.storePassword
                        keyAlias = signing.keyAlias
                        keyPassword = signing.keyPassword
                    }
                    buildTypes.getByName("release").signingConfig = release
                } else if (gradle.startParameter.taskNames.any { "release" in it.lowercase() }) {
                    logger.lifecycle("$path: no upload key on this machine ($application.signing.* in local.properties), the release is unsigned")
                }
            }
        }
    }
}

/** The upload key of an application and its passwords. [toString] never shows the passwords. */
class ReleaseSigning(val storeFile: File, val storePassword: String, val keyAlias: String, val keyPassword: String) {
    override fun toString(): String = "ReleaseSigning(storeFile=$storeFile, keyAlias=$keyAlias)"

    companion object {
        /** The key of [application] on this machine, or null when one of its four values is missing. */
        fun of(project: Project, application: String): ReleaseSigning? {
            fun value(name: String): String? = project.localProperty("$application.signing.$name")
                ?: project.providers.environmentVariable(environmentName(application, name)).orNull?.takeIf { it.isNotBlank() }
            val storeFile = value("storeFile") ?: return null
            return ReleaseSigning(
                storeFile = project.rootProject.file(storeFile),
                storePassword = value("storePassword") ?: return null,
                keyAlias = value("keyAlias") ?: return null,
                keyPassword = value("keyPassword") ?: return null,
            )
        }

        /** `PENCILNOTES_SIGNING_STORE_FILE` for `pencilnotes` and `storeFile`. */
        private fun environmentName(application: String, name: String): String =
            "${application.uppercase().replace('-', '_')}_SIGNING_" + name.replace(Regex("([a-z])([A-Z])"), "$1_$2").uppercase()
    }
}
