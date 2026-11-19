plugins {
    `kotlin-dsl`
}

dependencies {
    // The plugins are applied (and versioned) by the root build: compile only against them
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.mavenPublish.gradlePlugin)
    compileOnly(libs.dokka.gradlePlugin)
    compileOnly(libs.roborazzi.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "pageboard.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("composeLibrary") {
            id = "pageboard.compose.library"
            implementationClass = "ComposeLibraryConventionPlugin"
        }
        register("androidNative") {
            id = "pageboard.android.native"
            implementationClass = "AndroidNativeConventionPlugin"
        }
        register("androidRust") {
            id = "pageboard.android.rust"
            implementationClass = "AndroidRustConventionPlugin"
        }
        register("androidSigning") {
            id = "pageboard.android.signing"
            implementationClass = "AndroidSigningConventionPlugin"
        }
        register("jvmNatives") {
            id = "pageboard.jvm.natives"
            implementationClass = "JvmNativesConventionPlugin"
        }
        register("publish") {
            id = "pageboard.publish"
            implementationClass = "PublishConventionPlugin"
        }
        register("testData") {
            id = "pageboard.test.data"
            implementationClass = "TestDataConventionPlugin"
        }
        register("screenshots") {
            id = "pageboard.screenshots"
            implementationClass = "ScreenshotsConventionPlugin"
        }
        register("benchmarks") {
            id = "pageboard.benchmarks"
            implementationClass = "BenchmarksConventionPlugin"
        }
    }
}
