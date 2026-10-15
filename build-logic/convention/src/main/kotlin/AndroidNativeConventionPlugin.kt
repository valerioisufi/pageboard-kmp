import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * An Android library holding the shared library of one native engine of DrawView, built by the
 * NDK from `native/CMakeLists.txt` (the Kotlin Multiplatform Android library plugin cannot build
 * native code): the module sets the CMake target it builds and the arguments it needs.
 *
 * Published like the other modules: a Kotlin Multiplatform library depends on the native library
 * of its engine from its Android source set, so an application ships only the engines of the
 * modules it uses.
 */
class AndroidNativeConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("drawview.publish")

        val engine = name.removePrefix("drawview-native-")
        extensions.configure<LibraryExtension> {
            namespace = "io.github.valerioisufi.drawview.nativelibs.$engine"
            compileSdk = libs.version("android-compileSdk").toInt()
            ndkVersion = libs.version("android-ndk")

            defaultConfig {
                minSdk = libs.version("android-minSdk").toInt()
                ndk {
                    abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
                }
                externalNativeBuild {
                    cmake {
                        arguments += listOf("-DANDROID_STL=c++_static", "-DDRAWVIEW_BUILD_JNI=ON")
                    }
                }
            }

            externalNativeBuild {
                cmake {
                    path = rootProject.file("native/CMakeLists.txt")
                    version = libs.version("android-cmake")
                }
            }

            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
        }

        // The licenses of the engine (and of DrawView) in the library: its Java resources
        val licenses = licensesTask()
        extensions.configure<LibraryAndroidComponentsExtension> {
            onVariants { variant ->
                variant.sources.resources?.addGeneratedSourceDirectory(licenses, LicensesTask::output)
            }
        }
    }
}
