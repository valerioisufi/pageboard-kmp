// A desktop application with the editor of DrawView and no optional engine: it compiles without
// pageboard-pdf and pageboard-latex (and no store: pageboard-ui depends on none)
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

/** The version of DrawView checked (see the root build). */
val pageboardVersion: String = rootProject.extra["pageboardVersion"] as String

dependencies {
    implementation("io.github.valerioisufi:pageboard-ui:$pageboardVersion")
    implementation(compose.desktop.currentOs)
}

val checkWithoutEngines by tasks.registering {
    val artifacts = configurations.runtimeClasspath.flatMap { classpath ->
        classpath.incoming.artifacts.resolvedArtifacts.map { all -> all.map { it.id.componentIdentifier.displayName } }
    }
    inputs.property("artifacts", artifacts)
    doLast {
        val drawView = artifacts.get().filter { "pageboard-" in it }.map { it.substringAfter(":").substringBefore(":") }.toSortedSet()
        val unwanted = drawView.filter { it.startsWith("pageboard-pdf") || it.startsWith("pageboard-latex") || it.startsWith("pageboard-store") }
        check(unwanted.isEmpty()) { "pageboard-ui brings $unwanted" }
        println("pageboard-ui: $drawView")
    }
}

tasks.named("check") {
    dependsOn(checkWithoutEngines, tasks.named("compileKotlin"))
}
