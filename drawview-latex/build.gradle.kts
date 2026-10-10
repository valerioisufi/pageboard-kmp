import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi

plugins {
    id("drawview.compose.library")
    // The JNI library of MicroTeX in the desktop jar
    id("drawview.jvm.natives")
    alias(libs.plugins.kotlinSerialization)
}

description = "LaTeX formulas for DrawView, rendered by MicroTeX"

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

    android {
        // The TeX fonts are Compose resources: they must be packaged as Android assets
        androidResources {
            enable = true
        }
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
            implementation(project(":drawview-native-tex"))
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.valerioisufi.drawview.latex.generated.resources"
    publicResClass = false
}
