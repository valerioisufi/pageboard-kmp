plugins {
    id("drawview.compose.library")
}

description = "Export of DrawView documents as PDF files and images (the exporters and the use case, without their dialog)"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-canvas"))
            // The documents exported: those of the store of the application
            api(project(":drawview-store"))
            implementation(project(":drawview-pdf"))
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            // The texts of the documents drawn as in the editor, the formulas by MicroTeX (PdfExporterTest)
            implementation(project(":drawview-ui"))
            implementation(project(":drawview-latex"))
        }
    }
}
