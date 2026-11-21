plugins {
    id("pageboard.compose.library")
}

description = "Export of Pageboard documents as PDF files and images (the exporters and the use case, without their dialog)"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-canvas"))
            // The documents exported: those of the store of the application
            api(project(":pageboard-store"))
            implementation(project(":pageboard-pdf"))
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            // The texts of the documents drawn as in the editor, the formulas by MicroTeX (PdfExporterTest)
            implementation(project(":pageboard-ui"))
            implementation(project(":pageboard-latex"))
        }
    }
}
