// The libraries of Pageboard as another developer uses them: resolved from mavenLocal()
// (./consumer-checks/check.sh publishes them first), with only what each check declares.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenLocal()
        google()
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

// The JDK of a check (17 for the desktop: the oldest the libraries run on), found or downloaded
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "pageboard-consumer-checks"

include(":model-jvm")
include(":ui-desktop")
include(":canvas-android")
