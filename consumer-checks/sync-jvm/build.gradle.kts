// A JVM program sharing a document between two devices with the synchronization alone (see
// docs/plans/shared-access.md, phase 6): it runs, and no Compose (nor Skia) comes with it
plugins {
    alias(libs.plugins.kotlinJvm)
    application
}

kotlin {
    jvmToolchain(21)
}

/** The version of Pageboard checked (see the root build). */
val pageboardVersion: String = rootProject.extra["pageboardVersion"] as String

dependencies {
    implementation("io.github.valerioisufi:pageboard-sync:$pageboardVersion")
    implementation("io.github.valerioisufi:pageboard-store:$pageboardVersion")
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass = "SyncAloneKt"
}

val checkHeadless by tasks.registering {
    val artifacts = configurations.runtimeClasspath.flatMap { classpath ->
        classpath.incoming.artifacts.resolvedArtifacts.map { all -> all.map { it.id.componentIdentifier.displayName } }
    }
    inputs.property("artifacts", artifacts)
    doLast {
        val names = artifacts.get()
        val ui = names.filter { "compose" in it || "skiko" in it }
        check(ui.isEmpty()) { "pageboard-sync brings $ui" }
        println("pageboard-sync alone: ${names.size} artifacts, no Compose")
    }
}

tasks.named("check") {
    dependsOn(checkHeadless, tasks.named("run"))
}
