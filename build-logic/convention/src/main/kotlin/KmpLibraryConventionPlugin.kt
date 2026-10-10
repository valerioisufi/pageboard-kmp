import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.plugins.ExtensionAware
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.getByType
import org.jetbrains.kotlin.gradle.dsl.ExplicitApiMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * A Kotlin Multiplatform library of DrawView, published: Android, desktop JVM, iOS (device and
 * simulator).
 *
 * The Android namespace follows the name of the module (`drawview-sync-ui` →
 * `io.github.valerioisufi.drawview.sync.ui`). `-Pdrawview.explicitApi=warning` (or `strict`)
 * turns the explicit API mode on for every library, to measure what is still public by default.
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        pluginManager.apply("drawview.publish")

        val module = name
        extensions.configure<KotlinMultiplatformExtension> {
            jvm()
            iosArm64()
            iosSimulatorArm64()

            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = "io.github.valerioisufi.drawview." + module.removePrefix("drawview-").replace('-', '.')
                compileSdk = libs.version("android-compileSdk").toInt()
                minSdk = libs.version("android-minSdk").toInt()
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
            }

            compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }


            when (providers.gradleProperty("drawview.explicitApi").orNull) {
                "warning" -> explicitApi = ExplicitApiMode.Warning
                "strict" -> explicitApi = ExplicitApiMode.Strict
            }
        }
    }
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion
