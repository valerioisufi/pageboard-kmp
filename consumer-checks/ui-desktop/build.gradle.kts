// A desktop application with the editor of DrawView and no optional engine: it compiles without
// drawview-pdf and drawview-latex (and no store: drawview-ui depends on none)
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvmToolchain(21)
}

/** The version of DrawView checked (see the root build). */
val drawViewVersion: String = rootProject.extra["drawViewVersion"] as String

dependencies {
    implementation("io.github.valerioisufi:drawview-ui:$drawViewVersion")
    implementation(compose.desktop.currentOs)
}

val checkWithoutEngines by tasks.registering {
    val artifacts = configurations.runtimeClasspath.flatMap { classpath ->
        classpath.incoming.artifacts.resolvedArtifacts.map { all -> all.map { it.id.componentIdentifier.displayName } }
    }
    inputs.property("artifacts", artifacts)
    doLast {
        val drawView = artifacts.get().filter { "drawview-" in it }.map { it.substringAfter(":").substringBefore(":") }.toSortedSet()
        val unwanted = drawView.filter { it.startsWith("drawview-pdf") || it.startsWith("drawview-latex") || it.startsWith("drawview-store") }
        check(unwanted.isEmpty()) { "drawview-ui brings $unwanted" }
        println("drawview-ui: $drawView")
    }
}

tasks.named("check") {
    dependsOn(checkWithoutEngines, tasks.named("compileKotlin"))
}
