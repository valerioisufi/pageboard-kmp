plugins {
    id("pageboard.kmp.library")
}

description = "LiteRT-LM as the runtime of the local models of Pageboard (Gemma 4, EmbeddingGemma 2)"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-ml"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            implementation(libs.litertlm.android)
        }
        jvmMain.dependencies {
            // Its JNI libraries for every desktop platform it is built for, inside its jar
            implementation(libs.litertlm.jvm)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // The bench checks the answers in the JSON of their schema
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// The bench of the local models, never part of jvmTest: with the models of <scratch dir>/models
// (downloaded first when -Ppageboard.bench.download lists their ids), it writes <scratch dir>/bench.md:
//   ./gradlew :pageboard-ml-litert:jvmTest --tests '*LiteRtBench*' -Ppageboard.bench=<scratch dir>
val benchDirectory = providers.gradleProperty("pageboard.bench")
val benchDownloads = providers.gradleProperty("pageboard.bench.download")
tasks.named<Test>("jvmTest") {
    if (benchDirectory.isPresent) {
        systemProperty("pageboard.bench", benchDirectory.get())
        systemProperty("pageboard.bench.download", benchDownloads.getOrElse(""))
        systemProperty("pageboard.bench.models", providers.gradleProperty("pageboard.bench.models").getOrElse(""))
        outputs.upToDateWhen { false }
        testLogging.showStandardStreams = true
    }
}
