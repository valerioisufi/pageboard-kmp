import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("pageboard.compose.library")
    // The JNI library of the trackpad of macOS in the desktop jar
    id("pageboard.jvm.natives")
    alias(libs.plugins.kotlinSerialization)
}

description = "The Pageboard canvas: the editor of a document (controller, tools, gestures, selection), its tiled rendering, the ports of the engines"

// The trackpad of macOS is the only native library of the canvas
jvmNatives {
    platforms.set(listOf("darwin-aarch64", "darwin-x86_64"))
}

val nativeDir = rootProject.layout.projectDirectory.dir("native")

// The static libraries of the observer of the touches (Objective-C) for the iOS targets
val buildTouchObserverIos = listOf("iphoneos", "iphonesimulator").associateWith { sdk ->
    tasks.register<Exec>("buildTouchObserver-$sdk") {
        group = "native"
        description = "Builds the static library of the observer of the touches for $sdk"
        onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
        inputs.files(
            nativeDir.file("bridge/touch_observer.m"),
            nativeDir.file("bridge/touch_observer.h"),
            nativeDir.file("scripts/build_touch_observer.sh")
        ).withPropertyName("bridge").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("appleToolchain", appleToolchainVersion())
        val output = layout.buildDirectory.dir("touchobserver/$sdk")
        outputs.dir(output)
        outputs.cacheIf { true }
        commandLine("bash", nativeDir.file("scripts/build_touch_observer.sh").asFile.absolutePath, sdk, output.get().asFile.absolutePath)
    }
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

    // The observer of the touches of iOS, for the predicted and coalesced touches of the ink (an
    // Objective-C class: Kotlin/Native cannot override the methods UIGestureRecognizer leaves to subclasses)
    listOf(iosArm64() to "iphoneos", iosSimulatorArm64() to "iphonesimulator").forEach { (iosTarget, sdk) ->
        val interop = iosTarget.compilations.getByName("main").cinterops.create("touchobserver") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/touchobserver.def"))
            includeDirs(nativeDir.dir("bridge"))
            extraOpts("-libraryPath", layout.buildDirectory.dir("touchobserver/$sdk").get().asFile.absolutePath)
        }
        tasks.named(interop.interopProcessingTaskName) {
            dependsOn(buildTouchObserverIos.getValue(sdk))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-ink"))
            api(libs.compose.runtime)
            api(libs.compose.foundation)
            api(libs.compose.ui)
            api(libs.kotlinx.coroutines.core)
            api(libs.kotlinx.serialization.json)
            // Exposed by the public SyncLock typealias
            api(libs.atomicfu)
        }
        androidMain.dependencies {
            // The stroke in progress in a front buffer (docs/plans/ink-latency.md)
            implementation(libs.androidx.graphics.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            // The documents of the benchmarks (docs/plans/performance.md)
            implementation(project(":pageboard-store-testing"))
        }
    }
}
