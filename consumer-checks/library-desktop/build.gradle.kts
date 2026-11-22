// The minimal application of the README as another developer writes it: the library and the editor
// (pageboard-explorer), the notes in SQLite (pageboard-store-room) and the strokes of Google Ink
// (pageboard-ink), with nothing else declared. It compiles, and its store opens, takes a note and
// closes on Java 17, the oldest the libraries run on
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    application
}

kotlin {
    jvmToolchain(17)
}

/** The version of Pageboard checked (see the root build). */
val pageboardVersion: String = rootProject.extra["pageboardVersion"] as String

dependencies {
    implementation("io.github.valerioisufi:pageboard-explorer:$pageboardVersion")
    implementation("io.github.valerioisufi:pageboard-store-room:$pageboardVersion")
    implementation("io.github.valerioisufi:pageboard-ink:$pageboardVersion")
    implementation(compose.desktop.currentOs)
}

application {
    mainClass = "MyNotesKt"
}

tasks.named<JavaExec>("run") {
    // The store alone, without a window, in a directory of the build emptied first
    val data = layout.buildDirectory.dir("data")
    args("--store-in", data.get().asFile.absolutePath)
    doFirst { data.get().asFile.deleteRecursively() }
}

tasks.named("check") {
    dependsOn(tasks.named("run"))
}
