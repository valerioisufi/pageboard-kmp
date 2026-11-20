plugins {
    id("pageboard.kmp.library")
}

description = "Handwriting recognition for Pageboard, headless: the samples of the strokes, the ports of the readers, the lines of a block and the reading of it"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-model"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
