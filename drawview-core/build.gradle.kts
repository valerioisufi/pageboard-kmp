import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("drawview.compose.library")
    // The JNI library of the trackpad of macOS in the desktop jar
    id("drawview.jvm.natives")
    alias(libs.plugins.kotlinSerialization)
}

description = "The DrawView canvas: document model, changes, controller, tiled rendering, ink"

// The trackpad of macOS is the only native library of the canvas
jvmNatives {
    platforms.set(listOf("darwin-aarch64", "darwin-x86_64"))
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            // Targets whose Compose canvas is backed by Skia (skiko): desktop and iOS.
            group("skiko") {
                withJvm()
                withIos()
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-ink"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            // Exposed by the public SyncLock typealias
            api(libs.atomicfu)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
