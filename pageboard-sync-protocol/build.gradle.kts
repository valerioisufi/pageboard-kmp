plugins {
    id("pageboard.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

description = "The protocol of the DrawView synchronization, without the documents: the frames, the end-to-end encryption, the identities of the devices, the invitations, the membership, the transports"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-platform"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.cryptography.core)
            implementation(libs.cryptography.provider.optimal)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
