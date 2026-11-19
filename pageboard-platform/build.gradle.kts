plugins {
    id("pageboard.kmp.library")
}

description = "Platform services of DrawView: files, native libraries, secure store, device name, locks, time, SHA-256"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            // Inlined by syncLock (the lock of SyncLock)
            api(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
