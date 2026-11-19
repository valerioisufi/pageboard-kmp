plugins {
    id("pageboard.kmp.library")
}

description = "Synchronization of Pageboard documents on the local network: TCP transport, mDNS discovery"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-sync"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.network)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.jmdns)
        }
    }
}

// The sync of Pageboard is experimental for its users; the modules of the repository use it freely
kotlin {
    sourceSets.all {
        languageSettings.optIn("io.github.valerioisufi.pageboard.sync.protocol.ExperimentalSyncApi")
    }
}
