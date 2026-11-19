plugins {
    id("pageboard.kmp.library")
}

description = "The contract tests every Pageboard document store must pass, and documents for the benchmarks"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-store"))
            api(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
