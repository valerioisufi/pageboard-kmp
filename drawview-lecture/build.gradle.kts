import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
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
        // The audio codecs, libopus and vo-aacenc (native/scripts/build_ios.sh)
        val audio = iosTarget.compilations.getByName("main").cinterops.create("audiobridge") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/audiobridge.def"))
            includeDirs(nativeDir.dir("bridge"))
            extraOpts("-libraryPath", nativeDir.dir("build/ios/$sdk/lib").asFile.absolutePath)
        }
        tasks.named(audio.interopProcessingTaskName) {
            dependsOn(":buildIosNativeLibs-$sdk")
        }
    }

    jvm()

    android {
        namespace = "io.github.valerioisufi.drawview.lecture"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        // Strings are Compose resources (packaged as assets)
        androidResources {
            enable = true
        }
    }

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-ui"))
            implementation(project(":drawview-latex"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.atomicfu)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // The audio codecs (libaudio_bridge.so)
            implementation(project(":drawview-native"))
            // The permission of the microphone
            implementation(libs.androidx.activity.compose)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain {
            // The JNI bridge of whisper.cpp (native/scripts/build_desktop.sh)
            resources.srcDir(
                files(rootProject.layout.buildDirectory.dir("nativeLibs/jvm/drawview-lecture")).builtBy(":buildDesktopNativeLibs")
            )
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.valerioisufi.drawview.lecture.generated.resources"
}
