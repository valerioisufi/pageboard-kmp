plugins {
    id("pageboard.compose.library")
    // Screenshot tests of the dialog (recordRoborazziJvm, verifyRoborazziJvm)
    id("pageboard.screenshots")
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
            api(project(":pageboard-export"))
            api(project(":pageboard-ui"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
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
            implementation(project(":pageboard-latex"))
            implementation(libs.compose.uiTest)
            implementation(libs.roborazzi.composeDesktop)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.valerioisufi.pageboard.export.ui.generated.resources"
}
