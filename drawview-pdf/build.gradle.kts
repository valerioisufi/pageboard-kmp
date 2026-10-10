plugins {
    id("drawview.compose.library")
    // The JNI bridge of PDFium in the desktop jar
    id("drawview.jvm.natives")
}

description = "PDF documents for DrawView, read, rendered and written by PDFium"

kotlin {
    val nativeDir = rootProject.layout.projectDirectory.dir("native")
    listOf(
        iosArm64() to "iphoneos",
        iosSimulatorArm64() to "iphonesimulator"
    ).forEach { (iosTarget, sdk) ->
        // PDFium C API (native/scripts/build_ios.sh); PDFium itself is a dynamic framework linked
        // by the application (see pdfium.def)
        val pdfium = iosTarget.compilations.getByName("main").cinterops.create("pdfium") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/pdfium.def"))
            includeDirs(nativeDir.dir("bridge"))
            extraOpts("-libraryPath", nativeDir.dir("build/ios/$sdk/lib").asFile.absolutePath)
        }
        tasks.named(pdfium.interopProcessingTaskName) {
            dependsOn(":buildIosNativeLibs-$sdk", ":assemblePdfiumXcframework")
        }
    }

    android {
        // Required by the Compose resources tasks of the device tests
        androidResources {
            enable = true
        }
        // PDFium smoke test on a device/emulator: ./gradlew :drawview-pdf:connectedAndroidDeviceTest
        withDeviceTestBuilder {
            sourceSetTreeName = "test"
        }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-core"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.ui)
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            // libpdfium_bridge.so + libpdfium.so built/packaged with the NDK
            implementation(project(":drawview-native-pdfium"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.testExt.junit)
        }
        jvmMain {
            // libpdfium and its licenses (downloaded and verified by the root project)
            resources.srcDir(
                files(rootProject.layout.buildDirectory.dir("pdfium/jvmResources")).builtBy(":preparePdfiumJvmResources")
            )
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}
