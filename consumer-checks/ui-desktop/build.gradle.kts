// A desktop application with the editor of Pageboard and no optional engine: it compiles without
// pageboard-pdf and pageboard-latex (and no store: pageboard-ui depends on none)
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    // The oldest Java the libraries run on
    jvmToolchain(17)
}

/** The version of Pageboard checked (see the root build). */
val pageboardVersion: String = rootProject.extra["pageboardVersion"] as String

dependencies {
    // The versions of the libraries from their platform, as the README shows
    implementation(platform("io.github.valerioisufi:pageboard-bom:$pageboardVersion"))
    implementation("io.github.valerioisufi:pageboard-ui")
    implementation(compose.desktop.currentOs)
}

val checkWithoutEngines by tasks.registering {
    val artifacts = configurations.runtimeClasspath.flatMap { classpath ->
        classpath.incoming.artifacts.resolvedArtifacts.map { all -> all.map { it.id.componentIdentifier.displayName } }
    }
    inputs.property("artifacts", artifacts)
    doLast {
        val ours = artifacts.get().filter { "pageboard-" in it }.map { it.substringAfter(":").substringBefore(":") }.toSortedSet()
        val unwanted = ours.filter { it.startsWith("pageboard-pdf") || it.startsWith("pageboard-latex") || it.startsWith("pageboard-store") }
        check(unwanted.isEmpty()) { "pageboard-ui brings $unwanted" }
        println("pageboard-ui: $ours")
    }
}

tasks.named("check") {
    dependsOn(checkWithoutEngines, tasks.named("compileKotlin"))
}
