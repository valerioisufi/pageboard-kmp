import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    applyDefaultHierarchyTemplate {
        common {
            // Targets running MicroTeX with a Skia canvas: desktop (JNI) and iOS (cinterop).
            group("skiko") {
                withJvm()
                // iosMain itself depends on skikoMain (it uses the shared Skia TeX classes)
                group("ios") {
                    withIos()
                }
            }
        }
    }

    val nativeDir = rootProject.layout.projectDirectory.dir("native")
    listOf(
        iosArm64() to "iphoneos",
        iosSimulatorArm64() to "iphonesimulator"
    ).forEach { (iosTarget, sdk) ->
        iosTarget.binaries.framework {
            baseName = "DrawViewLatex"
            isStatic = true
        }
        // MicroTeX C API (native/scripts/build_ios.sh)
        val tex = iosTarget.compilations.getByName("main").cinterops.create("tex") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/tex.def"))
            includeDirs(nativeDir.dir("tex/port/capi"))
            extraOpts("-libraryPath", nativeDir.dir("build/ios/$sdk/lib").asFile.absolutePath)
        }
        tasks.named(tex.interopProcessingTaskName) {
            dependsOn(":buildIosNativeLibs-$sdk")
        }
    }

    jvm()

    android {
        namespace = "io.github.valerioisufi.drawview.latex"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        // The TeX fonts are Compose resources: they must be packaged as Android assets
        androidResources {
            enable = true
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
            implementation(libs.compose.components.resources)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // libtex.so built with the NDK
            implementation(project(":drawview-native"))
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        jvmMain {
            // JNI library of MicroTeX (native/scripts/build_desktop.sh)
            resources.srcDir(
                files(rootProject.layout.buildDirectory.dir("nativeLibs/jvm/drawview-latex")).builtBy(":buildDesktopNativeLibs")
            )
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.valerioisufi.drawview.latex.generated.resources"
    publicResClass = false
}
