plugins {
    id("pageboard.compose.library")
    alias(libs.plugins.kotlinSerialization)
    id("pageboard.screenshots")
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
            api(project(":pageboard-sync-lan"))
            api(project(":pageboard-ui"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
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

compose.resources {
    packageOfResClass = "io.github.valerioisufi.drawview.sync.ui.generated.resources"
}

// The sync of DrawView is experimental for its users; the modules of the repository use it freely
kotlin {
    sourceSets.all {
        languageSettings.optIn("io.github.valerioisufi.drawview.sync.protocol.ExperimentalSyncApi")
    }
}
