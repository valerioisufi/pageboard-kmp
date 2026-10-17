plugins {
    id("drawview.kmp.library")
}

description = "LiteRT-LM as the runtime of the local models of DrawView (Gemma 4, EmbeddingGemma 2)"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-ml"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
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
// (downloaded first when -Pdrawview.bench.download lists their ids), it writes <scratch dir>/bench.md:
//   ./gradlew :drawview-ml-litert:jvmTest --tests '*LiteRtBench*' -Pdrawview.bench=<scratch dir>
val benchDirectory = providers.gradleProperty("drawview.bench")
val benchDownloads = providers.gradleProperty("drawview.bench.download")
tasks.named<Test>("jvmTest") {
    if (benchDirectory.isPresent) {
        systemProperty("drawview.bench", benchDirectory.get())
        systemProperty("drawview.bench.download", benchDownloads.getOrElse(""))
        outputs.upToDateWhen { false }
        testLogging.showStandardStreams = true
    }
}
