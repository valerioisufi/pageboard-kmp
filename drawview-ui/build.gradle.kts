plugins {
    id("drawview.compose.library")
    alias(libs.plugins.kotlinSerialization)
    // Screenshot tests of the components (recordRoborazziJvm, verifyRoborazziJvm)
    alias(libs.plugins.roborazzi)
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
            api(project(":drawview-canvas"))
            api(project(":drawview-data"))
            api(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.navigationevent.compose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.uiTest)
            implementation(libs.roborazzi.composeDesktop)
        }
    }
}

// The reference images of the components (see ComponentScreenshotTest)
roborazzi {
    outputDir.set(layout.projectDirectory.dir("screenshots"))
}

compose.resources {
    publicResClass = true
    packageOfResClass = "io.github.valerioisufi.drawview.ui.generated.resources"
}
