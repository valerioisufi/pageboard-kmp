import org.gradle.api.Plugin
import org.gradle.api.Project

/**
 * A Compose Multiplatform library of Pageboard (see [KmpLibraryConventionPlugin]).
 *
 * A library never depends on `compose.desktop.currentOs`: the Skia runtime of an operating system
 * is chosen by the application (its tests may add it to `jvmTest`).
 */
class ComposeLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("pageboard.kmp.library")
        pluginManager.apply("org.jetbrains.compose")
        pluginManager.apply("org.jetbrains.kotlin.plugin.compose")
    }
}
