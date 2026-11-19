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
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.TestExecutable
import org.jetbrains.kotlin.konan.target.KonanTarget

/**
 * A Kotlin Multiplatform library of DrawView, published: Android, desktop JVM, iOS (device and
 * simulator).
 *
 * The Android namespace follows the name of the module (`pageboard-sync-ui` →
 * `io.github.valerioisufi.drawview.sync.ui`). The explicit API mode is strict: every public
 * declaration says so, with its type. A module whose native code reaches its classes by name keeps
 * them in its `consumer-rules.pro`, published with its Android library. The tests run on a data
 * directory of their own ([TestDataConventionPlugin]), the benchmarks apart
 * ([BenchmarksConventionPlugin]).
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        pluginManager.apply("com.android.kotlin.multiplatform.library")
        pluginManager.apply("pageboard.publish")
        pluginManager.apply("pageboard.test.data")
        pluginManager.apply("pageboard.benchmarks")

        val module = name
        extensions.configure<KotlinMultiplatformExtension> {
            jvm()
            iosArm64()
            iosSimulatorArm64()

            (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                namespace = "io.github.valerioisufi.drawview." + module.removePrefix("pageboard-").replace('-', '.')
                compileSdk = libs.version("android-compileSdk").toInt()
                minSdk = libs.version("android-minSdk").toInt()
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_11)
                }
                // The keep rules of what the native code of the module reaches by name, given to
                // the applications that shrink their code with R8
                val consumerRules = file("consumer-rules.pro")
                if (consumerRules.isFile) {
                    optimization.consumerKeepRules.publish = true
                    optimization.consumerKeepRules.file(consumerRules)
                }
            }

            compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
            }

            // PDFium is a dynamic framework the applications embed (pageboard-pdf links it without a
            // path): the iOS tests linked by Gradle find it in the slice of build/pdfium/PDFium.xcframework
            targets.withType(KotlinNativeTarget::class.java).configureEach {
                val slice = if (konanTarget == KonanTarget.IOS_ARM64) "ios-arm64" else "ios-arm64-simulator"
                val pdfium = rootProject.layout.buildDirectory.dir("pdfium/PDFium.xcframework/$slice").get().asFile.absolutePath
                binaries.withType(TestExecutable::class.java).configureEach {
                    linkerOpts("-F$pdfium", "-rpath", pdfium)
                }
            }

            // The public API is declared: what is not part of it is internal
            explicitApi = ExplicitApiMode.Strict

            // The licenses in the jars of the JVM and of Android
            val licenses = licensesTask().flatMap { it.output }
            sourceSets.named("jvmMain") { resources.srcDir(licenses) }
            sourceSets.named("androidMain") { resources.srcDir(licenses) }

            // The public API is recorded (api/), and every change of it is seen
            @OptIn(ExperimentalAbiValidation::class)
            abiValidation {
                // The lambdas Compose keeps in singletons are named by a hash of the compiler: not
                // an API, and different from a host to another
                filters { exclude { byNames.add("**ComposableSingletons**") } }
            }
        }
    }
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion
