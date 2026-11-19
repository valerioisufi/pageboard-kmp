plugins {
    id("pageboard.compose.library")
    id("pageboard.screenshots")
}

description = "Search in the notes of the DrawView document library, with snippets, model downloads and answers from the notes"

kotlin {
    android {
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-search"))
            api(project(":pageboard-explorer"))
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
    packageOfResClass = "io.github.valerioisufi.drawview.search.ui.generated.resources"
}
