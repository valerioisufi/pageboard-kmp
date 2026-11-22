import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.plugin.KotlinDependencyHandler

/**
 * The native library of the engine of a library on Android ([path], a `pageboard-native-*` module,
 * which the build has with the Android SDK only): a dependency when the build has it. Without it a
 * release (a version without `-SNAPSHOT`) fails its publication, since its Android variant would
 * come without its native library (GRASP Protected Variations, as `checkDesktopNativeLibraries` for
 * the desktop).
 */
fun KotlinDependencyHandler.androidNative(path: String) {
    val native = project.findProject(path)
    if (native != null) {
        implementation(native)
        return
    }
    val release = !project.providers.gradleProperty("VERSION_NAME").get().endsWith("-SNAPSHOT")
    if (!release) return
    val module = project.name
    project.tasks.matching { it.name.startsWith("publish") }.configureEach {
        doFirst {
            throw GradleException("$module would be published without $path: a release is built with the Android SDK")
        }
    }
}
