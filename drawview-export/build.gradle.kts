import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("drawview.compose.library")
    alias(libs.plugins.roborazzi)
}

description = "Export of DrawView documents as PDF files and images"

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

    android {
        // Strings are Compose resources (packaged as assets)
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-ui"))
            implementation(project(":drawview-pdf"))
            implementation(project(":drawview-latex"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(project(":drawview-latex"))
            implementation(libs.compose.uiTest)
            implementation(libs.roborazzi.composeDesktop)
        }
    }
}

// The reference images of the export (see ExportScreenshotTest)
roborazzi {
    outputDir.set(layout.projectDirectory.dir("screenshots"))
}

compose.resources {
    packageOfResClass = "io.github.valerioisufi.drawview.export.generated.resources"
}
