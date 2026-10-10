plugins {
    id("drawview.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

description = "Synchronization of DrawView documents between devices: protocol, end-to-end encryption, sync engine"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-data"))
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
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}
