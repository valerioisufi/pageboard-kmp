import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    val nativeDir = rootProject.layout.projectDirectory.dir("native")
    val pdfiumXcframework = rootProject.layout.buildDirectory.dir("pdfium/PDFium.xcframework").get()
    listOf(
        Triple(iosArm64(), "iphoneos", "ios-arm64"),
        Triple(iosSimulatorArm64(), "iphonesimulator", "ios-arm64-simulator")
    ).forEach { (iosTarget, sdk, slice) ->
        iosTarget.binaries.framework {
            baseName = "DrawViewPdf"
            isStatic = true
        }
        // PDFium C API (native/scripts/build_ios.sh). PDFium itself is a dynamic framework: the
        // executables linked by Gradle (tests) find it in the xcframework slice, whose absolute
        // path is appended to the definition file; the iOS app embeds it (iosApp/Configuration).
        val frameworkDir = pdfiumXcframework.dir(slice).asFile.absolutePath
        val generateDef = tasks.register("generatePdfiumDef${iosTarget.name.replaceFirstChar { it.uppercase() }}") {
            val template = layout.projectDirectory.file("src/nativeInterop/cinterop/pdfium.def")
            val output = layout.buildDirectory.file("cinterop/pdfium-$sdk.def")
            inputs.file(template)
            inputs.property("frameworkDir", frameworkDir)
            outputs.file(output)
            doLast {
                output.get().asFile.writeText(
                    template.asFile.readText().trimEnd() +
                        "\nlinkerOpts = -lc++ -F$frameworkDir -framework PDFium -rpath $frameworkDir\n"
                )
            }
        }
        val pdfium = iosTarget.compilations.getByName("main").cinterops.create("pdfium") {
            definitionFile.set(generateDef.map { layout.buildDirectory.file("cinterop/pdfium-$sdk.def").get() })
            includeDirs(nativeDir.dir("bridge"))
            extraOpts("-libraryPath", nativeDir.dir("build/ios/$sdk/lib").asFile.absolutePath)
        }
        tasks.named(pdfium.interopProcessingTaskName) {
            dependsOn(":buildIosNativeLibs-$sdk", ":assemblePdfiumXcframework")
        }
    }

    jvm()

    js {
        browser()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
    }

    android {
        namespace = "io.github.valerioisufi.drawview.pdf"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
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

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
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
            implementation(project(":drawview-native"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("androidDeviceTest").dependencies {
            implementation(libs.androidx.test.runner)
            implementation(libs.androidx.testExt.junit)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        jvmMain {
            // libpdfium + licenses (downloaded and verified by the root project) and the JNI
            // bridge (native/scripts/build_desktop.sh)
            resources.srcDir(
                files(rootProject.layout.buildDirectory.dir("pdfium/jvmResources")).builtBy(":preparePdfiumJvmResources")
            )
            resources.srcDir(
                files(rootProject.layout.buildDirectory.dir("nativeLibs/jvm/drawview-pdf")).builtBy(":buildDesktopNativeLibs")
            )
        }
    }
}
