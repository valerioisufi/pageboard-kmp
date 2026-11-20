plugins {
    id("pageboard.kmp.library")
}

description = "ML Kit Digital Ink Recognition as the reader of handwriting of Pageboard on Android"

kotlin {
    android {
        // The bench of ML Kit on a device or an emulator with Google Play services (phase 0 of
        // docs/plans/handwriting.md): ./gradlew :pageboard-handwriting-mlkit:connectedAndroidDeviceTest
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-handwriting"))
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.mlkit.digitalInk)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.testExt.junit)
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
