plugins {
    id("drawview.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

description = "Synchronization of DrawView documents between devices: the sync engine, its messages, the presence of the devices"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-store"))
            api(project(":drawview-sync-protocol"))
            implementation(libs.kotlinx.coroutines.core)
            // The files of the resources received, written as they come
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // The documents in Room, as in the applications (OfflineWorkBenchmark)
            implementation(project(":drawview-store-room"))
            // The strokes of the store modeled as the applications model them
            implementation(project(":drawview-ink"))
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}

// The sync of DrawView is experimental for its users; the modules of the repository use it freely
kotlin {
    sourceSets.all {
        languageSettings.optIn("io.github.valerioisufi.drawview.sync.protocol.ExperimentalSyncApi")
    }
}
