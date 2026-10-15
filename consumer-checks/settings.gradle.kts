// The libraries of DrawView as another developer uses them: resolved from mavenLocal()
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

rootProject.name = "drawview-consumer-checks"

include(":model-jvm")
include(":ui-desktop")
include(":canvas-android")
