// An iOS application on the libraries as another developer links it: a dynamic Kotlin framework for
// the simulator (Gradle links it here, where an application links it in Xcode) with the libraries
// that have native code, and the PDFium of the release (build/pdfium, as release.yml assembles it).
// It links: every static library of the klibs, the frameworks of their definitions and the Swift
// runtime of PencilKit are found. macOS only: elsewhere the link is skipped
plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

/** The version of Pageboard checked (see the root build). */
val pageboardVersion: String = rootProject.extra["pageboardVersion"] as String

/** The slice of the simulator of the PDFium of the applications (./gradlew assemblePdfiumXcframework). */
val pdfium: String = rootProject.layout.projectDirectory.dir("../build/pdfium/PDFium.xcframework/ios-arm64-simulator").asFile.absolutePath

kotlin {
    iosSimulatorArm64 {
        binaries.framework {
            baseName = "Consumer"
            isStatic = false
            linkerOpts("-F", pdfium, "-rpath", pdfium)
        }
    }

    sourceSets {
        iosMain.dependencies {
            // The versions of the libraries from their platform, as the README shows
            implementation(project.dependencies.platform("io.github.valerioisufi:pageboard-bom:$pageboardVersion"))
            implementation("io.github.valerioisufi:pageboard-explorer")
            implementation("io.github.valerioisufi:pageboard-store-room")
            implementation("io.github.valerioisufi:pageboard-ink")
            implementation("io.github.valerioisufi:pageboard-pdf")
            implementation("io.github.valerioisufi:pageboard-latex")
            implementation("io.github.valerioisufi:pageboard-export")
            implementation("io.github.valerioisufi:pageboard-sync-iroh")
            implementation("io.github.valerioisufi:pageboard-handwriting-pencilkit")
        }
    }
}

tasks.named("check") {
    dependsOn("linkDebugFrameworkIosSimulatorArm64")
}
