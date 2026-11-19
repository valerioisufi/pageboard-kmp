plugins {
    id("pageboard.android.native")
}

description = "PDFium of DrawView for Android (libpdfium.so and its JNI bridge libpdfium_bridge.so), used by pageboard-pdf"

android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                // The prebuilt packages of pdfium-binaries, one per ABI (libpdfium.so is packaged
                // with the bridge linked to it)
                arguments += "-DDRAWVIEW_PDFIUM_ROOT=${rootProject.layout.buildDirectory.dir("pdfium").get().asFile.absolutePath}"
                targets += "pdfium_bridge"
            }
        }
    }
}

// The PDFium packages of every ABI must be extracted before CMake configures the project
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(":preparePdfiumAndroid")
}
