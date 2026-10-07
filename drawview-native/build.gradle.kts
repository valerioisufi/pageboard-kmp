import java.io.StringReader
import java.util.Properties

plugins {
    alias(libs.plugins.androidLibrary)
}

/** The version of the source of an audio codec (native/<name>/<name>.properties). */
fun codecVersion(name: String): String = Properties().apply {
    load(StringReader(rootProject.file("native/$name/$name.properties").readText()))
}.getProperty("version")
val opusVersion = codecVersion("opus")
val aacVersion = codecVersion("aac")

/**
 * Android-only module that compiles the C++ engines shared by every platform with the NDK:
 * - `libink_bridge.so`: Google Ink stroke modeller (consumed by :drawview-core).
 * - `libtex.so`: MicroTeX LaTeX engine (consumed by :drawview-latex).
 * - `libpdfium_bridge.so`: JNI bridge to the prebuilt `libpdfium.so` of pdfium-binaries, which is
 *   packaged with it (consumed by :drawview-pdf).
 * - `libaudio_bridge.so`: the audio codecs of the lecture recordings, libopus and vo-aacenc
 *   (consumed by :drawview-lecture).
 *
 * The Kotlin Multiplatform Android library plugin does not support `externalNativeBuild`,
 * so the native build lives in this plain Android library which the KMP modules depend on.
 */
android {
    namespace = "io.github.valerioisufi.drawview.nativelibs"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.android.ndk.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_static",
                    "-DDRAWVIEW_BUILD_JNI=ON",
                    "-DDRAWVIEW_PDFIUM_ROOT=${rootProject.layout.buildDirectory.dir("pdfium").get().asFile.absolutePath}",
                    "-DDRAWVIEW_OPUS_DIR=${rootProject.layout.buildDirectory.dir("opus/src/opus-$opusVersion").get().asFile.absolutePath}",
                    "-DDRAWVIEW_AAC_DIR=${rootProject.layout.buildDirectory.dir("aac/src/vo-aacenc-$aacVersion").get().asFile.absolutePath}"
                )
                targets += listOf("ink_bridge", "tex", "pdfium_bridge", "audio_bridge")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = libs.versions.android.cmake.get()
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The PDFium packages of every ABI and the sources of the audio codecs must be extracted before
// CMake configures the project
tasks.configureEach {
    if (name.startsWith("configureCMake")) {
        dependsOn(":preparePdfiumAndroid", ":extractOpusSource", ":extractAacSource")
    }
}
