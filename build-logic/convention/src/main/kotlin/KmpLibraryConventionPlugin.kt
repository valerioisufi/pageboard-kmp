import com.android.build.api.dsl.KotlinMultiplatformAndroidLibraryTarget
import com.android.build.api.dsl.Lint
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.attributes.java.TargetJvmVersion
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
 * A Kotlin Multiplatform library of Pageboard, published: Android, the desktop JVM (Java 17 and
 * later), iOS (device and simulator). A module made for some of them only names them in its
 * `gradle.properties` (`pageboard.targets`, among `android`, `jvm` and `ios`, separated by commas):
 * the reader of handwriting of one platform publishes that platform only.
 *
 * The Android namespace follows the name of the module (`pageboard-sync-ui` →
 * `io.github.valerioisufi.pageboard.sync.ui`). The explicit API mode is strict: every public
 * declaration says so, with its type; what is public for the other modules only is
 * `@InternalPageboardApi`, which the libraries opt in to. A module whose native code reaches its
 * classes by name keeps them in its `consumer-rules.pro`, published with its Android library. The
 * tests run on a data directory of their own ([TestDataConventionPlugin]), the benchmarks apart
 * ([BenchmarksConventionPlugin]).
 */
class KmpLibraryConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val platforms = libraryTargets()
        pluginManager.apply("org.jetbrains.kotlin.multiplatform")
        if (ANDROID in platforms) {
            pluginManager.apply("com.android.kotlin.multiplatform.library")
            // The lint of the Android target (the Android-KMP plugin creates its tasks with this one)
            pluginManager.apply("com.android.lint")
        }
        pluginManager.apply("pageboard.publish")
        pluginManager.apply("pageboard.test.data")
        pluginManager.apply("pageboard.benchmarks")

        val module = name
        extensions.configure<KotlinMultiplatformExtension> {
            if (JVM in platforms) {
                jvm {
                    compilerOptions {
                        // Java 17 on the desktop, compiled against the API of the JDK 17: a member of
                        // a later JDK is an error of the compiler, not of the machine of a user
                        jvmTarget.set(JvmTarget.JVM_17)
                        freeCompilerArgs.add("-Xjdk-release=$DESKTOP_JAVA")
                    }
                }
            }
            if (IOS in platforms) {
                iosArm64()
                iosSimulatorArm64()
            }

            if (ANDROID in platforms) {
                (this as ExtensionAware).extensions.configure<KotlinMultiplatformAndroidLibraryTarget>("android") {
                    namespace = "io.github.valerioisufi.pageboard." + module.removePrefix("pageboard-").replace('-', '.')
                    compileSdk = libs.version("android-compileSdk").toInt()
                    minSdk = libs.version("android-minSdk").toInt()
                    compilerOptions {
                        jvmTarget.set(JvmTarget.JVM_11)
                    }
                    // The members that the minimum API does not have (lintAndroidMain): a call of one
                    // is an error here, not a NoSuchMethodError on the device of a user
                    lint {
                        checkOnly += "NewApi"
                        abortOnError = true
                    }
                    // The keep rules of what the native code of the module reaches by name, given to
                    // the applications that shrink their code with R8
                    val consumerRules = file("consumer-rules.pro")
                    if (consumerRules.isFile) {
                        optimization.consumerKeepRules.publish = true
                        optimization.consumerKeepRules.file(consumerRules)
                    }
                }
            }

            compilerOptions {
                freeCompilerArgs.add("-Xexpect-actual-classes")
                // What the modules of Pageboard share among themselves only
                optIn.add(INTERNAL_API)
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
            if (JVM in platforms) sourceSets.named("jvmMain") { resources.srcDir(licenses) }
            if (ANDROID in platforms) sourceSets.named("androidMain") { resources.srcDir(licenses) }

            // The public API is recorded (api/), and every change of it is seen
            @OptIn(ExperimentalAbiValidation::class)
            abiValidation {
                // The lambdas Compose keeps in singletons are named by a hash of the compiler: not
                // an API, and different from a host to another
                filters { exclude { byNames.add("**ComposableSingletons**") } }
            }
        }

        // What a library tells goes to the log of the platform, never to the standard output
        checkNoPrints()

        if (ANDROID in platforms) {
            // The lint of the JVM target (com.android.lint, part of check) on the same check: the
            // members of a later API are a question of Android, so it finds nothing there
            extensions.configure<Lint> {
                checkOnly += "NewApi"
                abortOnError = true
            }
        }

        if (JVM in platforms) {
            // The version of Java in the metadata of the desktop artifacts: an application on an
            // older one fails when it resolves them, and says why, instead of when it loads their
            // classes
            configurations.matching { it.name == "jvmApiElements" || it.name == "jvmRuntimeElements" }.configureEach {
                attributes.attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, DESKTOP_JAVA)
            }

            // The types of the public API come with the api dependencies (part of check)
            checkApiDependencies(if (ANDROID in platforms) "api/jvm/$module.api" else "api/$module.api")
        }
    }

    private companion object {
        /** The oldest Java the desktop artifacts run on. */
        const val DESKTOP_JAVA = 17

        /** The opt-in of what the modules of Pageboard share among themselves only. */
        const val INTERNAL_API = "io.github.valerioisufi.pageboard.platform.InternalPageboardApi"

        const val ANDROID = "android"
        const val JVM = "jvm"
        const val IOS = "ios"
    }

    /** The targets of this library: `pageboard.targets` of its `gradle.properties`, every one by default. */
    private fun Project.libraryTargets(): Set<String> {
        val all = setOf(ANDROID, JVM, IOS)
        val given = (findProperty("pageboard.targets") as String?)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet()
            ?: return all
        val unknown = given - all
        if (given.isEmpty() || unknown.isNotEmpty()) {
            throw GradleException("pageboard.targets of $name is '${given.joinToString(",")}': names among $all")
        }
        return given
    }
}

internal val Project.libs: VersionCatalog
    get() = extensions.getByType<VersionCatalogsExtension>().named("libs")

internal fun VersionCatalog.version(alias: String): String = findVersion(alias).get().requiredVersion
