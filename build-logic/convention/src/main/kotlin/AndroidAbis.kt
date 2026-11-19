import org.gradle.api.GradleException
import org.gradle.api.Project
import java.util.Properties

/** The Android ABIs of a release, and of a debug build unless the machine asks for less. */
val ANDROID_ABIS: List<String> = listOf("arm64-v8a", "armeabi-v7a", "x86_64")

/** A setting of this machine in `local.properties`, null when it does not have it. */
internal fun Project.localProperty(name: String): String? {
    val local = rootProject.file("local.properties")
    if (!local.isFile) return null
    return Properties().apply { local.inputStream().use(::load) }.getProperty(name)?.takeIf { it.isNotBlank() }
}

/**
 * The ABIs of the debug builds: the Gradle property `drawview.android.debug.abis`, as a workflow
 * passes it (the runners have no `local.properties`), else `android.debug.abis` in
 * `local.properties`, names separated by commas, else [ANDROID_ABIS].
 */
fun Project.debugAbis(): List<String> {
    val (source, configured) = providers.gradleProperty("drawview.android.debug.abis").orNull
        ?.takeIf { it.isNotBlank() }?.let { "drawview.android.debug.abis" to it }
        ?: localProperty("android.debug.abis")?.let { "android.debug.abis in local.properties" to it }
        ?: return ANDROID_ABIS
    val abis = configured.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val unknown = abis - ANDROID_ABIS.toSet()
    if (abis.isEmpty() || unknown.isNotEmpty()) {
        throw GradleException("$source is '$configured': names among $ANDROID_ABIS")
    }
    return abis
}
