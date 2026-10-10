plugins {
    `kotlin-dsl`
}

dependencies {
    // The plugins are applied (and versioned) by the root build: compile only against them
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.mavenPublish.gradlePlugin)
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
        register("publish") {
            id = "drawview.publish"
            implementationClass = "PublishConventionPlugin"
        }
    }
}
