plugins {
    id("pageboard.compose.library")
    // Screenshot tests of the components (recordRoborazziJvm, verifyRoborazziJvm)
    id("pageboard.screenshots")
}

description = "The Pageboard editor: screen, bars, tool popovers, dialogs, commands, theme, editor extensions"

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
            // The theme takes and gives the colors of Material 3 (ColorScheme)
            api(libs.compose.material3)
            implementation(libs.compose.ui)
            // The resources the other modules take from this one (StringResource)
            api(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
            // The files shared with the other applications (PageboardFileProvider)
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
    packageOfResClass = "io.github.valerioisufi.pageboard.ui.generated.resources"
}
