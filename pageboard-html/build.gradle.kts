plugins {
    id("pageboard.kmp.library")
}

description = "HTML turned into the Markdown of the DrawView texts: what other applications copy, without Compose"

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":pageboard-model"))
            implementation(libs.ksoup)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
