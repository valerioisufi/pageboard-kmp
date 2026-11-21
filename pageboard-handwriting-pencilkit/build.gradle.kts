plugins {
    id("pageboard.kmp.library")
    // The JNI library of the PencilKit bridge in the desktop jar (macOS)
    id("pageboard.jvm.natives")
}

description = "The handwriting recognizer of PencilKit as the reader of handwriting of Pageboard on iOS, iPadOS and macOS 27"

// macOS only on the desktop: the bridge calls PencilKit
jvmNatives {
    platforms.set(listOf("darwin-aarch64", "darwin-x86_64"))
}

val nativeDir = rootProject.layout.projectDirectory.dir("native")

// The static libraries of the PencilKit bridge (Swift) for the iOS targets
val buildPencilKitBridgeIos = listOf("iphoneos", "iphonesimulator").associateWith { sdk ->
    tasks.register<Exec>("buildPencilKitBridge-$sdk") {
        group = "native"
        description = "Builds the static library of the PencilKit bridge for $sdk"
        onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
        inputs.files(
            nativeDir.file("bridge/pencilkit_bridge.swift"),
            nativeDir.file("bridge/pencilkit_bridge.h"),
            nativeDir.file("scripts/build_pencilkit.sh")
        ).withPropertyName("bridge").withPathSensitivity(PathSensitivity.RELATIVE)
        inputs.property("appleToolchain", appleToolchainVersion())
        val output = layout.buildDirectory.dir("pencilkit/$sdk")
        outputs.dir(output)
        outputs.cacheIf { true }
        commandLine("bash", nativeDir.file("scripts/build_pencilkit.sh").asFile.absolutePath, sdk, output.get().asFile.absolutePath)
    }
}

kotlin {
    listOf(iosArm64() to "iphoneos", iosSimulatorArm64() to "iphonesimulator").forEach { (iosTarget, sdk) ->
        val interop = iosTarget.compilations.getByName("main").cinterops.create("pencilkitbridge") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/pencilkitbridge.def"))
            includeDirs(nativeDir.dir("bridge"))
            extraOpts("-libraryPath", layout.buildDirectory.dir("pencilkit/$sdk").get().asFile.absolutePath)
        }
        tasks.named(interop.interopProcessingTaskName) {
            dependsOn(buildPencilKitBridgeIos.getValue(sdk))
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-handwriting"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(project(":pageboard-platform"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
