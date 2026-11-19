// A JVM program using the document model alone: it runs, and no Compose (nor Skia) comes with it
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
    implementation("io.github.valerioisufi:pageboard-model:$pageboardVersion")
}

application {
    mainClass = "ModelAloneKt"
}

val checkHeadless by tasks.registering {
    val artifacts = configurations.runtimeClasspath.flatMap { classpath ->
        classpath.incoming.artifacts.resolvedArtifacts.map { all -> all.map { it.id.componentIdentifier.displayName } }
    }
    inputs.property("artifacts", artifacts)
    doLast {
        val names = artifacts.get()
        val ui = names.filter { "compose" in it || "skiko" in it || "androidx" in it }
        check(ui.isEmpty()) { "pageboard-model brings $ui" }
        println("pageboard-model alone: ${names.size} artifacts, no Compose: ${names.joinToString()}")
    }
}

tasks.named("check") {
    dependsOn(checkHeadless, tasks.named("run"))
}
