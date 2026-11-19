plugins {
    id("pageboard.compose.library")
    // Screenshot tests of the components (recordRoborazziJvm, verifyRoborazziJvm)
    id("pageboard.screenshots")
}

description = "The DrawView editor: screen, bars, tool popovers, dialogs, commands, theme, editor extensions"

kotlin {
    android {
        // Strings, icons and the color wheel mask are Compose resources (packaged as assets)
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-canvas"))
            implementation(project(":pageboard-html"))
            implementation(libs.navigationevent.compose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // The files shared with the other applications (DrawViewFileProvider)
            implementation(libs.androidx.core.ktx)
        }
        jvmMain.dependencies {
            // The title bars of the windows, set through the APIs of macOS and Windows
            // (MacWindowHelper, WindowsDwmHelper)
            implementation(libs.jna)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.uiTest)
            implementation(libs.roborazzi.composeDesktop)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "io.github.valerioisufi.pageboard.ui.generated.resources"
}
