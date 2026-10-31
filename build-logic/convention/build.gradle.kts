plugins {
    `kotlin-dsl`
}

dependencies {
    // The plugins are applied (and versioned) by the root build: compile only against them
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.mavenPublish.gradlePlugin)
    compileOnly(libs.dokka.gradlePlugin)
}

gradlePlugin {
    plugins {
        register("kmpLibrary") {
            id = "drawview.kmp.library"
            implementationClass = "KmpLibraryConventionPlugin"
        }
        register("composeLibrary") {
            id = "drawview.compose.library"
            implementationClass = "ComposeLibraryConventionPlugin"
        }
        register("androidNative") {
            id = "drawview.android.native"
            implementationClass = "AndroidNativeConventionPlugin"
        }
        register("androidRust") {
            id = "drawview.android.rust"
            implementationClass = "AndroidRustConventionPlugin"
        }
        register("jvmNatives") {
            id = "drawview.jvm.natives"
            implementationClass = "JvmNativesConventionPlugin"
        }
        register("publish") {
            id = "drawview.publish"
            implementationClass = "PublishConventionPlugin"
        }
        register("testData") {
            id = "drawview.test.data"
            implementationClass = "TestDataConventionPlugin"
        }
        register("benchmarks") {
            id = "drawview.benchmarks"
            implementationClass = "BenchmarksConventionPlugin"
        }
    }
}
