plugins {
    id("drawview.compose.library")
    // The JNI bridges of whisper.cpp and of the audio codecs in the desktop jar
    id("drawview.jvm.natives")
    alias(libs.plugins.kotlinSerialization)
}

description = "The DrawView lecture assistant without its UI: recording, transcription, the models, the lectures of the documents and their sharing"

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

    android {
        // The strings of the notification of the recording service (Android resources)
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-model"))
            // The state of the assistant, observed by its UI (the runtime only: no UI, no Skia)
            api(libs.compose.runtime)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.atomicfu)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(project(":drawview-lecture-testing"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // The audio codecs (libaudio_bridge.so)
            implementation(project(":drawview-native-audio"))
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}
