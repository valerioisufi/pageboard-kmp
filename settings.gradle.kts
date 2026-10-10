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

include(":drawview-native-ink")
include(":drawview-native-tex")
include(":drawview-native-pdfium")
include(":drawview-native-audio")
include(":drawview-platform")
include(":drawview-model")
include(":drawview-ink")
include(":drawview-canvas")
include(":drawview-data")
include(":drawview-latex")
include(":drawview-pdf")
include(":drawview-ui")
include(":drawview-explorer")
include(":drawview-lecture")
include(":drawview-sync")
include(":drawview-sync-lan")
include(":drawview-sync-ui")
include(":drawview-export")

// Sample application: how to build an application with the DrawView modules
include(":sample-shared")
include(":sample-android")
include(":sample-desktop")
project(":sample-shared").projectDir = file("sample/shared")
project(":sample-android").projectDir = file("sample/androidApp")
project(":sample-desktop").projectDir = file("sample/desktopApp")

// PencilNotes: the note-taking application built on DrawView
include(":pencilnotes-shared")
include(":pencilnotes-android")
include(":pencilnotes-desktop")
project(":pencilnotes-shared").projectDir = file("pencilnotes/shared")
project(":pencilnotes-android").projectDir = file("pencilnotes/androidApp")
project(":pencilnotes-desktop").projectDir = file("pencilnotes/desktopApp")
