plugins {
    id("drawview.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

description = "The DrawView document model, headless: pages, elements, changes, clock, geometry"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-platform"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
