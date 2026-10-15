plugins {
    id("drawview.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

description = "Storage of DrawView documents, headless: the store ports, the open documents, a key-value store"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-model"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(project(":drawview-store-testing"))
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
