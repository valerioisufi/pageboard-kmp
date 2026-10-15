plugins {
    id("drawview.compose.library")
    // Screenshot tests of the panel (recordRoborazziJvm, verifyRoborazziJvm)
    alias(libs.plugins.roborazzi)
}

description = "The DrawView lecture assistant in an application: its panel, its settings, the recording in the editor and in the library"

kotlin {
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
            api(project(":drawview-lecture"))
            api(project(":drawview-ui"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // The permission of the microphone
            implementation(libs.androidx.activity.compose)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(project(":drawview-lecture-testing"))
            implementation(libs.compose.uiTest)
            implementation(libs.roborazzi.composeDesktop)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.valerioisufi.drawview.lecture.ui.generated.resources"
}

// The reference images of the panel (see LecturePanelScreenshotTest)
roborazzi {
    outputDir.set(layout.projectDirectory.dir("screenshots"))
}
