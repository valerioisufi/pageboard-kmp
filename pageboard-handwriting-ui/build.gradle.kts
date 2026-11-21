plugins {
    id("pageboard.compose.library")
    id("pageboard.screenshots")
}

description = "Handwriting converted into text in the Pageboard editor: the command on a selection of strokes, the preview, the text box in their place"

kotlin {
    android {
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-handwriting"))
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
            implementation(libs.compose.uiTest)
            implementation(libs.roborazzi.composeDesktop)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.valerioisufi.pageboard.handwriting.ui.generated.resources"
}
