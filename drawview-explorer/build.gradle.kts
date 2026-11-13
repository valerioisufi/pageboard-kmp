plugins {
    id("drawview.compose.library")
    // Screenshot tests of the library and of the editor (recordRoborazziJvm, verifyRoborazziJvm)
    alias(libs.plugins.roborazzi)
}

description = "The DrawView library of documents and folders, with thumbnails and the editor"

kotlin {
    android {
        // Strings and icons are Compose resources (packaged as assets)
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-session"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            api(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.navigationevent.compose)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.core.ktx)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            // The engines of the applications (FileImportTest, the screenshots of the formulas)
            implementation(project(":drawview-pdf"))
            implementation(project(":drawview-latex"))
            implementation(libs.compose.uiTest)
            implementation(libs.roborazzi.composeDesktop)
            // The thumbnails of a note of the Room store (ThumbnailBenchmark)
            implementation(project(":drawview-store-room"))
            implementation(libs.androidx.room.runtime)
            implementation(project(":drawview-store-testing"))
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "io.github.valerioisufi.drawview.explorer.generated.resources"
}

// The reference images of the screenshot tests (see AdaptiveScreenshotTest)
roborazzi {
    outputDir.set(layout.projectDirectory.dir("screenshots"))
}
