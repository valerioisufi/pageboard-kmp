plugins {
    id("pageboard.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

description = "Search in the notes of a Pageboard library (full-text and embeddings) and answers from the passages found"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-store"))
            api(project(":pageboard-ml"))
            api(project(":pageboard-handwriting"))
            implementation(libs.androidx.sqlite.bundled)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            // The benchmark of the indexer, on the store of the applications (docs/plans/performance.md)
            implementation(project(":pageboard-store-room"))
            // The strokes of the store modeled as the applications model them
            implementation(project(":pageboard-ink"))
            implementation(project(":pageboard-store-testing"))
            implementation(libs.androidx.room.runtime)
        }
    }
}
