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
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}
