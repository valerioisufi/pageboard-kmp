plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.androidxRoom) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.kotlinSerialization) apply false
}
// =====================================================================================
// iOS native engines (Google Ink + MicroTeX), linked statically through cinterop.
// Each task builds native/build/ios/<sdk>/lib/libdrawview_{ink,tex}.a
// =====================================================================================
val nativeSourcesDir = layout.projectDirectory.dir("native")
listOf("iphoneos", "iphonesimulator").forEach { sdk ->
    tasks.register<Exec>("buildIosNativeLibs-$sdk") {
        group = "native"
        description = "Builds the Google Ink and MicroTeX static libraries for the $sdk SDK"
        onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
        inputs.files(
            fileTree(nativeSourcesDir) {
                include("CMakeLists.txt", "bridge/**", "tex/src/**", "tex/port/**", "ink/ink/**", "external/**", "scripts/build_ios.sh")
                exclude("build/**")
            }
        ).withPropertyName("nativeSources").withPathSensitivity(PathSensitivity.RELATIVE)
        outputs.dir(nativeSourcesDir.dir("build/ios/$sdk/lib")).withPropertyName("libraries")
        workingDir(nativeSourcesDir)
        commandLine("bash", "scripts/build_ios.sh", sdk)
    }
}
