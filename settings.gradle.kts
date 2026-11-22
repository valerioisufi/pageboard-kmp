rootProject.name = "pageboard-kmp"

pluginManagement {
    // Convention plugins of the libraries (pageboard.kmp.library, pageboard.compose.library, pageboard.publish)
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
    if (providers.gradleProperty("pageboard.android.disabled").orNull == "true") return@run false
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
    include(":pageboard-native-ink")
    include(":pageboard-native-tex")
    include(":pageboard-native-pdfium")
    include(":pageboard-native-audio")
    include(":pageboard-native-iroh")
}
include(":pageboard-bom")
include(":pageboard-platform")
include(":pageboard-model")
include(":pageboard-html")
include(":pageboard-ink")
include(":pageboard-canvas")
include(":pageboard-store")
include(":pageboard-store-room")
include(":pageboard-store-testing")
include(":pageboard-latex")
include(":pageboard-pdf")
include(":pageboard-ui")
include(":pageboard-session")
include(":pageboard-explorer")
include(":pageboard-ml")
include(":pageboard-handwriting")
include(":pageboard-handwriting-mlkit")
include(":pageboard-handwriting-pencilkit")
include(":pageboard-handwriting-windows")
include(":pageboard-handwriting-ui")
include(":pageboard-ml-litert")
include(":pageboard-search")
include(":pageboard-search-ui")
include(":pageboard-agent")
include(":pageboard-agent-ui")
include(":pageboard-lecture")
include(":pageboard-lecture-ui")
include(":pageboard-lecture-testing")
include(":pageboard-sync-protocol")
include(":pageboard-sync")
include(":pageboard-sync-lan")
include(":pageboard-sync-iroh")
include(":pageboard-sync-ui")
include(":pageboard-export")
include(":pageboard-export-ui")

// Sample application: how to build an application with the Pageboard modules
include(":sample-shared")
include(":sample-desktop")
project(":sample-shared").projectDir = file("sample/shared")
project(":sample-desktop").projectDir = file("sample/desktopApp")
if (hasAndroidSdk) {
    include(":sample-android")
    project(":sample-android").projectDir = file("sample/androidApp")
}

// PencilNotes: the note-taking application built on Pageboard
include(":pencilnotes-shared")
include(":pencilnotes-desktop")
project(":pencilnotes-shared").projectDir = file("pencilnotes/shared")
project(":pencilnotes-desktop").projectDir = file("pencilnotes/desktopApp")
if (hasAndroidSdk) {
    include(":pencilnotes-android")
    project(":pencilnotes-android").projectDir = file("pencilnotes/androidApp")
}
