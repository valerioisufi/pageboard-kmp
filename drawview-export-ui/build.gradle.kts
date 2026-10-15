plugins {
    id("drawview.compose.library")
    // Screenshot tests of the dialog (recordRoborazziJvm, verifyRoborazziJvm)
    alias(libs.plugins.roborazzi)
}

description = "The export of DrawView documents in an application: the dialog, \"Export\" in the library and in the editor"

kotlin {
    android {
        // Strings are Compose resources (packaged as assets)
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-export"))
            api(project(":drawview-ui"))
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

// The reference images of the dialog (see ExportScreenshotTest)
roborazzi {
    outputDir.set(layout.projectDirectory.dir("screenshots"))
}

compose.resources {
    packageOfResClass = "io.github.valerioisufi.drawview.export.ui.generated.resources"
}
