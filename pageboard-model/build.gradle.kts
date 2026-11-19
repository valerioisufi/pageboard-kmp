plugins {
    id("pageboard.kmp.library")
    alias(libs.plugins.kotlinSerialization)
}

description = "The Pageboard document model, headless: pages, elements, changes, clock, geometry"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-platform"))
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            // The values of the elements of the applications (ElementType)
            implementation(libs.kotlinx.serialization.cbor)
            // Read while the document is changed (the counter of the page render keys)
            implementation(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
