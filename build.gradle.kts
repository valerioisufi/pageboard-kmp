import java.io.StringReader
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Properties
import javax.inject.Inject

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
// iOS native engines (Google Ink, MicroTeX, PDFium bridge), linked statically through cinterop.
// Each task builds native/build/ios/<sdk>/lib/libdrawview_{ink,tex,pdf}.a
// =====================================================================================
val nativeSourcesDir = layout.projectDirectory.dir("native")
mapOf("iphoneos" to "ios-device-arm64", "iphonesimulator" to "ios-simulator-arm64").forEach { (sdk, pdfiumPackage) ->
    tasks.register<Exec>("buildIosNativeLibs-$sdk") {
        group = "native"
        description = "Builds the Google Ink, MicroTeX and PDFium bridge static libraries for the $sdk SDK"
        onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
        dependsOn(":extractPdfium-$pdfiumPackage")
        environment("DRAWVIEW_PDFIUM_DIR", layout.buildDirectory.dir("pdfium/$pdfiumPackage").get().asFile.absolutePath)
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

// =====================================================================================
// PDFium: prebuilt packages of bblanchon/pdfium-binaries, downloaded on demand, verified
// against the SHA-256 checksums of native/pdfium/pdfium.properties and extracted into
// build/pdfium/<package>/ (lib/, include/, licenses/). No PDFium binary is versioned.
//   preparePdfiumDesktop       host package (-Pdrawview.pdfium.desktop=all: every desktop package)
//   preparePdfiumJvmResources  libpdfium + licenses as jvmMain resources of :drawview-pdf
//   preparePdfiumAndroid       armeabi-v7a, arm64-v8a, x86_64 packages
//   assemblePdfiumXcframework  build/pdfium/PDFium.xcframework (device + simulator, macOS only)
// =====================================================================================

/** Downloads [url] into [destination], failing unless its SHA-256 digest is [sha256]. */
abstract class DownloadVerifiedFile : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputFile
    abstract val destination: RegularFileProperty

    @TaskAction
    fun download() {
        val target = destination.get().asFile
        val partial = File(target.parentFile, "${target.name}.part")
        target.parentFile.mkdirs()

        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(URI(url.get()).toURL().openStream(), digest).use { input ->
            partial.outputStream().use { input.copyTo(it) }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(sha256.get(), ignoreCase = true)) {
            partial.delete()
            throw GradleException("Checksum mismatch for ${url.get()}: expected ${sha256.get()}, got $actual")
        }
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

/**
 * Extracts a pdfium-binaries archive. The macOS dylibs carry a relative install name
 * (./libpdfium.dylib): it is rewritten to @rpath/libpdfium.dylib so that the JNI bridge finds
 * the library next to itself.
 */
abstract class ExtractPdfium @Inject constructor(
    private val archives: ArchiveOperations,
    private val fileSystem: FileSystemOperations,
    private val execOperations: ExecOperations
) : DefaultTask() {
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val archive: RegularFileProperty

    @get:Input
    abstract val fixMacInstallName: Property<Boolean>

    @get:OutputDirectory
    abstract val destination: DirectoryProperty

    @TaskAction
    fun extract() {
        fileSystem.sync {
            from(archives.tarTree(archives.gzip(archive.get().asFile)))
            into(destination)
        }
        if (fixMacInstallName.get()) {
            val dylib = destination.file("lib/libpdfium.dylib").get().asFile.absolutePath
            execOperations.exec { commandLine("install_name_tool", "-id", "@rpath/libpdfium.dylib", dylib) }
            // install_name_tool invalidates the signature (mandatory on arm64): re-sign ad hoc
            execOperations.exec { commandLine("codesign", "--force", "--sign", "-", dylib) }
        }
    }
}

val pdfiumProperties = Properties().apply {
    load(StringReader(providers.fileContents(layout.projectDirectory.file("native/pdfium/pdfium.properties")).asText.get()))
}
val pdfiumVersion: String = pdfiumProperties.getProperty("version")
val pdfiumBuildDir = layout.buildDirectory.dir("pdfium")
val isMacHost = System.getProperty("os.name").lowercase().contains("mac")

/** pdfium-binaries package name -> resource folder of JvmNativeLibraries.platformFolder */
val pdfiumDesktopPlatforms = mapOf(
    "mac-arm64" to "darwin-aarch64",
    "mac-x64" to "darwin-x86_64",
    "linux-x64" to "linux-x86_64",
    "linux-arm64" to "linux-aarch64",
    "win-x64" to "windows-x86_64",
    "win-arm64" to "windows-aarch64",
)
val pdfiumHostPackage: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val cpu = if (arch.contains("aarch64") || arch.contains("arm64")) "arm64" else "x64"
    when {
        os.contains("mac") -> "mac-$cpu"
        os.contains("win") -> "win-$cpu"
        else -> "linux-$cpu"
    }
}
val pdfiumDesktopPackages: List<String> =
    if (providers.gradleProperty("drawview.pdfium.desktop").orNull == "all") pdfiumDesktopPlatforms.keys.toList()
    else listOf(pdfiumHostPackage)
val pdfiumAndroidPackages = listOf("android-arm", "android-arm64", "android-x64")
val pdfiumIosPackages = listOf("ios-device-arm64", "ios-simulator-arm64")

val extractPdfiumTasks = (pdfiumDesktopPlatforms.keys + pdfiumAndroidPackages + pdfiumIosPackages).associateWith { pkg ->
    val sha256 = pdfiumProperties.getProperty("sha256.$pkg")
        ?: throw GradleException("Missing sha256.$pkg in native/pdfium/pdfium.properties")
    val download = tasks.register<DownloadVerifiedFile>("downloadPdfium-$pkg") {
        group = "pdfium"
        description = "Downloads and verifies pdfium-$pkg.tgz (chromium/$pdfiumVersion)"
        url.set("https://github.com/bblanchon/pdfium-binaries/releases/download/chromium%2F$pdfiumVersion/pdfium-$pkg.tgz")
        this.sha256.set(sha256)
        destination.set(pdfiumBuildDir.map { it.file("downloads/$pdfiumVersion/pdfium-$pkg.tgz") })
    }
    tasks.register<ExtractPdfium>("extractPdfium-$pkg") {
        group = "pdfium"
        description = "Extracts pdfium-$pkg into build/pdfium/$pkg"
        archive.set(download.flatMap { it.destination })
        fixMacInstallName.set(pkg.startsWith("mac-") && isMacHost)
        destination.set(pdfiumBuildDir.map { it.dir(pkg) })
    }
}

tasks.register("preparePdfiumDesktop") {
    group = "pdfium"
    description = "Extracts the PDFium packages of the desktop platforms: ${pdfiumDesktopPackages.joinToString()}"
    dependsOn(pdfiumDesktopPackages.map { extractPdfiumTasks.getValue(it) })
}

tasks.register<Sync>("preparePdfiumJvmResources") {
    group = "pdfium"
    description = "Collects libpdfium and its licenses as resources of the desktop JVM artifacts"
    pdfiumDesktopPackages.forEach { pkg ->
        val extract = extractPdfiumTasks.getValue(pkg)
        val platformFolder = pdfiumDesktopPlatforms.getValue(pkg)
        from(extract.flatMap { it.destination }) {
            include("lib/libpdfium.dylib", "lib/libpdfium.so", "bin/pdfium.dll", "LICENSE", "licenses/**")
            eachFile {
                path = when {
                    name == "LICENSE" -> "native/$platformFolder/PDFIUM_LICENSE.txt"
                    path.startsWith("licenses/") -> "native/$platformFolder/$path"
                    else -> "native/$platformFolder/$name"
                }
            }
            includeEmptyDirs = false
        }
    }
    into(pdfiumBuildDir.map { it.dir("jvmResources") })
}

// =====================================================================================
// Desktop JVM native engines (Google Ink, MicroTeX, PDFium bridge): JNI libraries built by
// native/scripts/build_desktop.sh into build/nativeLibs/jvm/<module>/native/<platform>/, added
// to the jvmMain resources of :drawview-core, :drawview-latex and :drawview-pdf.
//   -Pdrawview.desktop.platform=<platform>  platform to build (default: the host; macOS hosts can
//                                           also build the other darwin architecture)
//   -Pdrawview.native.prebuilt=true         skip the build: build/nativeLibs/jvm already holds the
//                                           libraries of every platform (CI release job)
// =====================================================================================
val desktopPlatform: String = providers.gradleProperty("drawview.desktop.platform").orNull
    ?: pdfiumDesktopPlatforms.getValue(pdfiumHostPackage)
val desktopPdfiumPackage: String = pdfiumDesktopPlatforms.entries.firstOrNull { it.value == desktopPlatform }?.key
    ?: throw GradleException("Unknown desktop platform '$desktopPlatform': one of ${pdfiumDesktopPlatforms.values}")
val nativeLibsPrebuilt = providers.gradleProperty("drawview.native.prebuilt").orNull == "true"

tasks.register<Exec>("buildDesktopNativeLibs") {
    group = "native"
    description = "Builds the Google Ink, MicroTeX, PDFium and trackpad (macOS) bridge JNI libraries for $desktopPlatform"
    val prebuilt = nativeLibsPrebuilt
    onlyIf { !prebuilt }
    val pdfium = extractPdfiumTasks.getValue(desktopPdfiumPackage)
    if (!prebuilt) dependsOn(pdfium)
    inputs.files(
        fileTree(nativeSourcesDir) {
            include("CMakeLists.txt", "bridge/**", "tex/src/**", "tex/port/**", "ink/ink/**", "ink/LICENSE", "external/**", "scripts/build_desktop.sh")
            exclude("build/**")
        }
    ).withPropertyName("nativeSources").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("platform", desktopPlatform)
    listOf("drawview-core", "drawview-latex", "drawview-pdf").forEach { module ->
        outputs.dir(layout.buildDirectory.dir("nativeLibs/jvm/$module/native/$desktopPlatform")).withPropertyName(module)
    }
    environment("DRAWVIEW_PDFIUM_DIR", pdfiumBuildDir.get().dir(desktopPdfiumPackage).asFile.absolutePath)
    // JNI headers of the JDK running Gradle
    environment("JAVA_HOME", System.getProperty("java.home"))
    workingDir(nativeSourcesDir)
    commandLine("bash", "scripts/build_desktop.sh", desktopPlatform)
}

tasks.register("preparePdfiumAndroid") {
    group = "pdfium"
    description = "Extracts the PDFium packages of the Android ABIs"
    dependsOn(pdfiumAndroidPackages.map { extractPdfiumTasks.getValue(it) })
}

tasks.register<Exec>("assemblePdfiumXcframework") {
    group = "pdfium"
    description = "Wraps the iOS PDFium dylibs into build/pdfium/PDFium.xcframework"
    val macHost = isMacHost
    onlyIf { macHost }
    val packageDirs = pdfiumIosPackages.map { pkg -> extractPdfiumTasks.getValue(pkg).flatMap { it.destination } }
    inputs.files(packageDirs).withPropertyName("packages").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file("native/scripts/make_pdfium_xcframework.sh").withPropertyName("script")
    val output = pdfiumBuildDir.map { it.dir("PDFium.xcframework") }
    outputs.dir(output).withPropertyName("xcframework")
    commandLine(
        listOf("bash", "native/scripts/make_pdfium_xcframework.sh", pdfiumVersion, output.get().asFile.absolutePath) +
            pdfiumIosPackages.map { pdfiumBuildDir.get().dir(it).asFile.absolutePath }
    )
}

tasks.register<Exec>("embedPdfiumForXcode") {
    group = "pdfium"
    description = "Embeds PDFium.framework into the iOS app being built (run by the Xcode build phase)"
    dependsOn("assemblePdfiumXcframework")
    commandLine("bash", "native/scripts/embed_pdfium_xcode.sh")
}
