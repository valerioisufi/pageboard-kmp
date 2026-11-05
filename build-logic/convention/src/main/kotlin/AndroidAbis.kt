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
 * The ABIs of the debug builds of this machine: `android.debug.abis` in `local.properties`, names
 * separated by commas, else [ANDROID_ABIS].
 */
fun Project.debugAbis(): List<String> {
    val configured = localProperty("android.debug.abis") ?: return ANDROID_ABIS
    val abis = configured.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    val unknown = abis - ANDROID_ABIS.toSet()
    if (abis.isEmpty() || unknown.isNotEmpty()) {
        throw GradleException("android.debug.abis in local.properties is '$configured': names among $ANDROID_ABIS")
    }
    return abis
}
