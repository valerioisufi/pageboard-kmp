plugins {
    id("drawview.compose.library")
}

description = "The DrawView editor of the documents of a store: opening a document, writing its changes and the preferences"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-ui"))
            api(project(":drawview-store"))
            api(libs.androidx.lifecycle.viewmodelCompose)
            // The stop of the screen of the editor, when the text being written is saved
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // The editor draws with Skia on the desktop
            implementation(compose.desktop.currentOs)
            implementation(libs.compose.uiTest)
            // The text written read back from the store of the applications (see TextWrittenSurvivesRoomTest)
            implementation(project(":drawview-store-room"))
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}
