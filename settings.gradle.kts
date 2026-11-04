rootProject.name = "DrawView-KMP"

pluginManagement {
    // Convention plugins of the libraries (drawview.kmp.library, drawview.compose.library, drawview.publish)
    includeBuild("build-logic")
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

// Android-only modules: require the Android SDK (com.android.library / com.android.application).
// If no Android SDK is configured (e.g. on a minimal Linux ARM64 desktop build host), these
// modules are skipped so desktop tasks can be configured and built without an Android SDK.
val hasAndroidSdk: Boolean = run {
    if (providers.gradleProperty("drawview.android.disabled").orNull == "true") return@run false
    val env = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    if (env != null && file(env).isDirectory) return@run true
    val localProps = file("local.properties")
    if (localProps.isFile) {
        val props = java.util.Properties().apply {
            localProps.inputStream().use { load(it) }
        }
        val sdkDir = props.getProperty("sdk.dir")
        if (sdkDir != null && file(sdkDir).isDirectory) return@run true
    }
    false
}

if (hasAndroidSdk) {
    include(":drawview-native-ink")
    include(":drawview-native-tex")
    include(":drawview-native-pdfium")
    include(":drawview-native-audio")
    include(":drawview-native-iroh")
}
include(":drawview-platform")
include(":drawview-model")
include(":drawview-ink")
include(":drawview-canvas")
include(":drawview-store")
include(":drawview-store-room")
include(":drawview-store-testing")
include(":drawview-latex")
include(":drawview-pdf")
include(":drawview-ui")
include(":drawview-session")
include(":drawview-explorer")
include(":drawview-ml")
include(":drawview-ml-litert")
include(":drawview-search")
include(":drawview-search-ui")
include(":drawview-agent")
include(":drawview-agent-ui")
include(":drawview-lecture")
include(":drawview-lecture-ui")
include(":drawview-lecture-testing")
include(":drawview-sync-protocol")
include(":drawview-sync")
include(":drawview-sync-lan")
include(":drawview-sync-iroh")
include(":drawview-sync-ui")
include(":drawview-export")
include(":drawview-export-ui")

// Sample application: how to build an application with the DrawView modules
include(":sample-shared")
include(":sample-desktop")
project(":sample-shared").projectDir = file("sample/shared")
project(":sample-desktop").projectDir = file("sample/desktopApp")
if (hasAndroidSdk) {
    include(":sample-android")
    project(":sample-android").projectDir = file("sample/androidApp")
}

// PencilNotes: the note-taking application built on DrawView
include(":pencilnotes-shared")
include(":pencilnotes-desktop")
project(":pencilnotes-shared").projectDir = file("pencilnotes/shared")
project(":pencilnotes-desktop").projectDir = file("pencilnotes/desktopApp")
if (hasAndroidSdk) {
    include(":pencilnotes-android")
    project(":pencilnotes-android").projectDir = file("pencilnotes/androidApp")
}
