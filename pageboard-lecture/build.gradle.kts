plugins {
    id("pageboard.compose.library")
    // The JNI bridges of whisper.cpp and of the audio codecs in the desktop jar
    id("pageboard.jvm.natives")
    alias(libs.plugins.kotlinSerialization)
}

description = "The Pageboard lecture assistant without its UI: recording, transcription, the models, the lectures of the documents and their sharing"

kotlin {
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
            api(project(":pageboard-model"))
            // The models of the device: their catalog, downloads and the models in memory (DeviceModels)
            api(project(":pageboard-ml"))
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
            implementation(project(":pageboard-lecture-testing"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            // The audio codecs (libaudio_bridge.so)
            if (findProject(":pageboard-native-audio") != null) {
                implementation(project(":pageboard-native-audio"))
            }
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmTest.dependencies {
            // The bench of the models on the device (LiteRT-LM)
            implementation(project(":pageboard-ml-litert"))
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

// The bench of the assistant on the models of the device, never part of jvmTest: with the models of
// <bench>/models and the material of <bench>/material, it writes <bench>/assistant-bench.md:
//   ./gradlew :pageboard-lecture:jvmTest --tests '*AssistantBench*' -Ppageboard.bench=<bench>
//     [-Ppageboard.bench.parts=formulas,prompts,speech,search] [-Ppageboard.bench.workspace=<lecture-eval workspace>]
val benchDirectory = providers.gradleProperty("pageboard.bench")
tasks.named<Test>("jvmTest") {
    if (benchDirectory.isPresent) {
        systemProperty("pageboard.bench", benchDirectory.get())
        systemProperty("pageboard.bench.parts", providers.gradleProperty("pageboard.bench.parts").getOrElse(""))
        systemProperty("pageboard.bench.workspace", providers.gradleProperty("pageboard.bench.workspace").getOrElse(""))
        systemProperty("pageboard.bench.models", providers.gradleProperty("pageboard.bench.models").getOrElse(""))
        systemProperty("pageboard.bench.modes", providers.gradleProperty("pageboard.bench.modes").getOrElse(""))
        for (name in listOf("temperature", "kinds", "lectures", "accelerator", "notes", "noteLectures", "interval", "dollars")) {
            systemProperty("pageboard.bench.$name", providers.gradleProperty("pageboard.bench.$name").getOrElse(""))
        }
        outputs.upToDateWhen { false }
        testLogging.showStandardStreams = true
        // The models answer slowly on the processor
        maxHeapSize = "4g"
    }
}
