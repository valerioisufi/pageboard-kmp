plugins {
    id("pageboard.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

description = "Synchronization of Pageboard documents between devices: the sync engine, its messages, the presence of the devices"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-store"))
            api(project(":pageboard-sync-protocol"))
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
            implementation(project(":pageboard-store-room"))
            // The strokes of the store modeled as the applications model them
            implementation(project(":pageboard-ink"))
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
    }
}

// The sync of Pageboard is experimental for its users; the modules of the repository use it freely
kotlin {
    sourceSets.all {
        languageSettings.optIn("io.github.valerioisufi.pageboard.sync.protocol.ExperimentalSyncApi")
    }
}

// The sharing states of a copy of a database read as accesses (docs/plans/shared-access.md),
// never run by jvmTest:
//   ./gradlew :pageboard-sync:jvmTest --tests '*SharingOfACopyCheck*' -Ppageboard.sharingCopy=<file of document<TAB>secret lines>
val sharingCopy = providers.gradleProperty("pageboard.sharingCopy")
tasks.withType<Test>().configureEach {
    if (sharingCopy.isPresent) {
        systemProperty("pageboard.sharingCopy", sharingCopy.get())
        testLogging { showStandardStreams = true }
        outputs.upToDateWhen { false }
    }
}
