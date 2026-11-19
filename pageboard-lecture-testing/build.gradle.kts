plugins {
    id("pageboard.kmp.library")
}

description = "The test doubles of the DrawView lecture assistant: a microphone, a notebook, the secrets in memory"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-lecture"))
        }
    }
}
