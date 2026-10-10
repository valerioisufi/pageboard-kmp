import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * The JNI libraries of a module on the desktop JVM, built by `:buildDesktopNativeLibs` into
 * `build/nativeLibs/jvm/<module>/native/<platform>/` and packaged as resources of its jar (loaded
 * by `JvmNativeLibraries`).
 *
 * A local build holds the libraries of the machine building it only. A release (a version
 * without `-SNAPSHOT`) is published with the libraries of every desktop platform, built by the CI
 * and given with `-Pdrawview.native.prebuilt=true`: publishing one without them fails.
 */
class JvmNativesConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val libraries = rootProject.layout.buildDirectory.dir("nativeLibs/jvm/$name")
        extensions.configure<KotlinMultiplatformExtension> {
            sourceSets.getByName("jvmMain").resources.srcDir(files(libraries).builtBy(":buildDesktopNativeLibs"))
        }

        val release = !providers.gradleProperty("VERSION_NAME").get().endsWith("-SNAPSHOT")
        val check = tasks.register("checkDesktopNativeLibraries") {
            group = "verification"
            description = "Checks that the jar holds the JNI libraries of every desktop platform (releases)"
            val folder = libraries.map { it.dir("native").asFile }
            onlyIf { release }
            doLast {
                val missing = DESKTOP_PLATFORMS.filterNot { folder.get().resolve(it).isDirectory }
                if (missing.isNotEmpty()) {
                    throw GradleException(
                        "The JNI libraries of ${missing.joinToString()} are missing from ${folder.get()}: " +
                            "a release is published with the libraries of every desktop platform (-Pdrawview.native.prebuilt=true)"
                    )
                }
            }
        }
        tasks.configureEach {
            if (name.startsWith("publish") && name.contains("Jvm")) dependsOn(check)
        }
    }

    private companion object {
        /** The folders of `JvmNativeLibraries.platformFolder`. */
        val DESKTOP_PLATFORMS = listOf(
            "darwin-aarch64", "darwin-x86_64", "linux-x86_64", "linux-aarch64", "windows-x86_64", "windows-aarch64"
        )
    }
}
