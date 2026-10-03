plugins {
    alias(libs.plugins.androidLibrary)
}

/**
 * Android-only module that compiles the C++ engines shared by every platform with the NDK:
 * - `libink_bridge.so`: Google Ink stroke modeller (consumed by :drawview-core).
 * - `libtex.so`: MicroTeX LaTeX engine (consumed by :drawview-latex).
 * - `libpdfium_bridge.so`: JNI bridge to the prebuilt `libpdfium.so` of pdfium-binaries, which is
 *   packaged with it (consumed by :drawview-pdf).
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
                    "-DDRAWVIEW_PDFIUM_ROOT=${rootProject.layout.buildDirectory.dir("pdfium").get().asFile.absolutePath}"
                )
                targets += listOf("ink_bridge", "tex", "pdfium_bridge")
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

// The PDFium packages of every ABI must be extracted before CMake configures the project
tasks.configureEach {
    if (name.startsWith("configureCMake")) {
        dependsOn(":preparePdfiumAndroid")
    }
}
