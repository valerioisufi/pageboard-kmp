import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("drawview.compose.library")
    // The JNI library of Google Ink in the desktop jar
    id("drawview.jvm.natives")
    alias(libs.plugins.kotlinSerialization)
}

description = "The DrawView canvas: document model, changes, controller, tiled rendering, ink"

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

    val nativeDir = rootProject.layout.projectDirectory.dir("native")
    listOf(
        iosArm64() to "iphoneos",
        iosSimulatorArm64() to "iphonesimulator"
    ).forEach { (iosTarget, sdk) ->
        // Google Ink C++ engine (native/scripts/build_ios.sh)
        val inkBridge = iosTarget.compilations.getByName("main").cinterops.create("inkbridge") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/inkbridge.def"))
            includeDirs(nativeDir.dir("bridge"))
            extraOpts("-libraryPath", nativeDir.dir("build/ios/$sdk/lib").asFile.absolutePath)
        }
        tasks.named(inkBridge.interopProcessingTaskName) {
            dependsOn(":buildIosNativeLibs-$sdk")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-platform"))
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
        androidMain.dependencies {
            // libink_bridge.so built with the NDK
            implementation(project(":drawview-native-ink"))
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
