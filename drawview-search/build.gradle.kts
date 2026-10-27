plugins {
    id("drawview.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

description = "Search in the notes of a DrawView library (full-text and embeddings) and answers from the passages found"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-store"))
            api(project(":drawview-ml"))
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
