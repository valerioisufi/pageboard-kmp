import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipFile

// An Android application with the canvas of DrawView: among the native libraries of DrawView,
// its APK holds the one of Google Ink only (no PDFium, MicroTeX nor audio codecs)
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

/** The version of DrawView checked (see the root build). */
val pageboardVersion: String = rootProject.extra["pageboardVersion"] as String

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

dependencies {
    implementation("io.github.valerioisufi:pageboard-canvas:$pageboardVersion")
    implementation(libs.androidx.activity.compose)
}

android {
    namespace = "io.github.valerioisufi.pageboard.consumer"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig {
        applicationId = "io.github.valerioisufi.pageboard.consumer"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

val checkNativeLibraries by tasks.registering {
    dependsOn("assembleDebug")
    val apks = layout.buildDirectory.dir("outputs/apk/debug")
    doLast {
        val apk = apks.get().asFile.listFiles().orEmpty().single { it.extension == "apk" }
        val libraries = ZipFile(apk).use { zip -> zip.entries().toList().map { it.name }.filter { it.startsWith("lib/") }.map { it.substringAfterLast('/') }.toSortedSet() }
        val drawView = libraries.filter { it in setOf("libink_bridge.so", "libtex.so", "libpdfium.so", "libpdfium_bridge.so", "libaudio_bridge.so") }
        check(drawView == listOf("libink_bridge.so")) { "The native libraries of DrawView in the APK: $drawView" }
        println("pageboard-canvas on Android: $libraries")
    }
}

tasks.named("check") {
    dependsOn(checkNativeLibraries)
}
