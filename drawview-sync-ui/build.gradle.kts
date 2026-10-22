plugins {
    id("drawview.compose.library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.roborazzi)
}

description = "Collaboration in the DrawView editor: sharing, invitations, people on the document"

kotlin {
    android {
        // Strings are Compose resources (packaged as assets)
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-sync-lan"))
            api(project(":drawview-ui"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            // The QR code of the invitations
            implementation(libs.qrose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.kotlinx.coroutines.core)
            // The settings of the synchronization over the internet, kept as JSON
            implementation(libs.kotlinx.serialization.json)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlin.test)
            implementation(libs.compose.uiTest)
            implementation(libs.roborazzi.composeDesktop)
        }
    }
}

// The reference images of the sharing (see SharingScreenshotTest)
roborazzi {
    outputDir.set(layout.projectDirectory.dir("screenshots"))
}

compose.resources {
    packageOfResClass = "io.github.valerioisufi.drawview.sync.ui.generated.resources"
}
