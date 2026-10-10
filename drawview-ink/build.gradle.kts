plugins {
    id("drawview.kmp.library")
    // The JNI library of Google Ink in the desktop jar
    id("drawview.jvm.natives")
}

description = "Google Ink stroke modeller of DrawView: the strokes as the editor draws them, native on every platform"

kotlin {
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
            api(project(":drawview-model"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            // libink_bridge.so built with the NDK
            implementation(project(":drawview-native-ink"))
        }
    }
}
