plugins {
    id("pageboard.kmp.library")
}

description = "The local models of Pageboard: their catalog, their downloads, the models in memory and the port of the runtime that runs them"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-platform"))
            api(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.io.core)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
