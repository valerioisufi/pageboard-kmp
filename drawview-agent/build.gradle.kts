plugins {
    id("drawview.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

description = "What an external model can read of a DrawView document and propose for it, reviewed by the user"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-store"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
