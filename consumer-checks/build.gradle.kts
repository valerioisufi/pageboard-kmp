plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
}

/** The version of the libraries checked (the one of the build of DrawView). */
val pageboardVersion: String = providers.fileContents(layout.projectDirectory.file("../gradle.properties")).asText.get()
    .lines().first { it.startsWith("VERSION_NAME=") }.substringAfter('=')

extra["pageboardVersion"] = pageboardVersion
