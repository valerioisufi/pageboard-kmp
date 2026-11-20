plugins {
    id("pageboard.kmp.library")
}

description = "Handwriting recognition for Pageboard, headless: the samples of the strokes, the ports of the readers, the lines of a block and the reading of it"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-model"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}

// The bench of the readers (phase 0 of docs/plans/handwriting.md), never run by jvmTest:
//   ./gradlew :pageboard-handwriting:jvmTest --tests '*HandwritingBench*' -Ppageboard.bench=<directory>
//     [-Ppageboard.bench.parts=generate,lines,score]
val benchDirectory = providers.gradleProperty("pageboard.bench")
tasks.withType<Test>().configureEach {
    if (benchDirectory.isPresent) {
        systemProperty("pageboard.bench", benchDirectory.get())
        systemProperty("pageboard.bench.parts", providers.gradleProperty("pageboard.bench.parts").getOrElse(""))
        testLogging { showStandardStreams = true }
        outputs.upToDateWhen { false }
    }
}
