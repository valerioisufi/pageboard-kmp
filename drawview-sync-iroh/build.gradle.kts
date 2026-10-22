plugins {
    id("drawview.kmp.library")
    // The JNI library of the iroh bridge in the desktop jar
    id("drawview.jvm.natives")
}

description = "Synchronization of DrawView documents over the internet: iroh connections by device key, direct or through a relay"

val irohBridge = rootProject.layout.projectDirectory.dir("native/iroh-bridge")
val bridgeSources = fileTree(irohBridge) { exclude("target/**") }

// The static libraries of the iroh bridge (Rust) for the iOS targets, without their debug
// symbols, for the C code of the crates the minimum iOS of Kotlin/Native
val buildIrohBridgeIos = tasks.register<CargoBuild>("buildIrohBridgeIos") {
    group = "native"
    description = "Builds the static libraries of the iroh bridge for the iOS device and simulator"
    onlyIf { System.getProperty("os.name").lowercase().contains("mac") }
    sources.from(bridgeSources)
    crate.set(irohBridge)
    library.set("iroh_bridge")
    cargo.set(cargoExecutable())
    crateType.set("staticlib")
    targets.set(mapOf("aarch64-apple-ios" to "iphoneos", "aarch64-apple-ios-sim" to "iphonesimulator"))
    environment.set(mapOf("IPHONEOS_DEPLOYMENT_TARGET" to "15.0"))
    stripDebugSymbols.set(true)
    targetDirectory.set(rustTargetDirectory)
    output.set(layout.buildDirectory.dir("rust/ios"))
}

// The relay of the tests (plain HTTP on the loopback), started by the tests of the JVM
val devRelay = rustTargetDirectory.resolve("release/dev_relay" + if (System.getProperty("os.name").lowercase().startsWith("windows")) ".exe" else "")
val buildIrohDevRelay = tasks.register<Exec>("buildIrohDevRelay") {
    group = "native"
    description = "Builds the relay of the tests of the iroh bridge"
    inputs.files(bridgeSources).withPathSensitivity(PathSensitivity.RELATIVE)
    outputs.file(devRelay)
    val cargo = cargoExecutable()
    workingDir(irohBridge)
    environment("PATH", cargoSearchPath(cargo))
    environment("CARGO_TARGET_DIR", rustTargetDirectory.absolutePath)
    commandLine(cargo, "build", "--release", "--locked", "--features", "dev-relay", "--bin", "dev_relay")
}

kotlin {
    listOf(iosArm64() to "iphoneos", iosSimulatorArm64() to "iphonesimulator").forEach { (iosTarget, sdk) ->
        val interop = iosTarget.compilations.getByName("main").cinterops.create("irohbridge") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/irohbridge.def"))
            includeDirs(irohBridge.dir("include"))
            extraOpts("-libraryPath", layout.buildDirectory.dir("rust/ios/$sdk").get().asFile.absolutePath)
        }
        tasks.named(interop.interopProcessingTaskName) {
            dependsOn(buildIrohBridgeIos)
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-sync"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // libiroh_bridge.so built with the NDK
            if (findProject(":drawview-native-iroh") != null) {
                implementation(project(":drawview-native-iroh"))
            }
        }
    }
}

tasks.named<Test>("jvmTest") {
    dependsOn(buildIrohDevRelay)
    systemProperty("drawview.iroh.devRelay", devRelay.absolutePath)
}

// The notices of the Rust crates inside the libraries of the module
tasks.named<LicensesTask>("drawviewLicenses") {
    licenses.from(rootProject.file("native/iroh-bridge/THIRD_PARTY_NOTICES.md"))
}
