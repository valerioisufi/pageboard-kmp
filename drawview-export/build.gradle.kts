import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("drawview.compose.library")
}

description = "Export of DrawView documents as PDF files and images (the exporters and the use case, without their dialog)"

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            // Targets whose Compose canvas is backed by Skia (skiko): desktop and iOS.
            group("skiko") {
                withJvm()
                withIos()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-canvas"))
            // The documents exported: those of the store of the application
            api(project(":drawview-store"))
            implementation(project(":drawview-pdf"))
            implementation(project(":drawview-latex"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            // The texts of the documents drawn as in the editor (PdfExporterTest)
            implementation(project(":drawview-ui"))
        }
    }
}
