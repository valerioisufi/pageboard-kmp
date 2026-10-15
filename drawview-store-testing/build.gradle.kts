plugins {
    id("drawview.kmp.library")
}

description = "The contract tests every DrawView document store must pass"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-store"))
            api(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
