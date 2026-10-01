plugins {
    alias(libs.plugins.androidLibrary)
}

/**
 * Android-only module that compiles the C++ engines shared by every platform with the NDK:
 * - `libink_bridge.so`: Google Ink stroke modeller (consumed by :drawview-core).
 * - `libtex.so`: MicroTeX LaTeX engine (consumed by :drawview-latex).
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
                    "-DDRAWVIEW_BUILD_JNI=ON"
                )
                targets += listOf("ink_bridge", "tex")
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
