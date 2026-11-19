import com.android.build.api.dsl.LibraryExtension
import com.android.build.api.variant.LibraryAndroidComponentsExtension
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register

/**
 * An Android library holding the shared library of a Rust crate of DrawView, built by cargo with
 * the NDK of the module for each ABI ([CargoBuild]): the module names the crate and its library
 * ([AndroidRustExtension]).
 *
 * Published like the other native libraries: a Kotlin Multiplatform library depends on it from
 * its Android source set.
 */
class AndroidRustConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("com.android.library")
        pluginManager.apply("pageboard.publish")

        val rust = extensions.create<AndroidRustExtension>("androidRust")
        val engine = name.removePrefix("pageboard-native-")
        val minSdk = libs.version("android-minSdk").toInt()
        extensions.configure<LibraryExtension> {
            namespace = "io.github.valerioisufi.pageboard.nativelibs.$engine"
            compileSdk = libs.version("android-compileSdk").toInt()
            ndkVersion = libs.version("android-ndk")
            defaultConfig {
                this.minSdk = minSdk
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_11
                targetCompatibility = JavaVersion.VERSION_11
            }
        }

        val components = extensions.getByType<LibraryAndroidComponentsExtension>()
        // The licenses of the crate (and of DrawView) in the library: its Java resources
        val licenses = licensesTask()
        val debugAbis = debugAbis()
        components.onVariants { variant ->
            // A release holds every ABI, a debug build those of this machine
            val abis = if (variant.buildType == "debug") debugAbis else ANDROID_ABIS
            val build = tasks.register<CargoBuild>("buildRustLibraries" + variant.name.replaceFirstChar { it.uppercase() }) {
                group = "native"
                description = "Builds the Rust library of the module for the Android ABIs of ${variant.name}"
                sources.from(rust.crate.map { crate -> fileTree(crate) { exclude("target/**") } })
                crate.set(rust.crate)
                library.set(rust.library)
                cargo.set(cargoExecutable())
                crateType.set("cdylib")
                targets.set(ABIS.filterValues { it.abi in abis }.mapValues { it.value.abi })
                environment.set(components.sdkComponents.ndkDirectory.map { ndk -> linkers(ndk.asFile, minSdk) })
                targetDirectory.set(rustTargetDirectory)
                output.set(layout.buildDirectory.dir("rust/jniLibs/${variant.name}"))
            }
            variant.sources.jniLibs?.addGeneratedSourceDirectory(build, CargoBuild::output)
            variant.sources.resources?.addGeneratedSourceDirectory(licenses, LicensesTask::output)
        }
    }

    private class Abi(val abi: String, val clang: String)

    private companion object {
        /** The Rust target of each ABI, with the prefix of the clang of the NDK building for it. */
        val ABIS = mapOf(
            "aarch64-linux-android" to Abi("arm64-v8a", "aarch64-linux-android"),
            "armv7-linux-androideabi" to Abi("armeabi-v7a", "armv7a-linux-androideabi"),
            "x86_64-linux-android" to Abi("x86_64", "x86_64-linux-android"),
        )

        /** The linker and the C compiler of each target (the C code of the crates: ring...). */
        fun linkers(ndk: java.io.File, minSdk: Int): Map<String, String> {
            val os = System.getProperty("os.name").lowercase()
            val host = when {
                os.startsWith("windows") -> "windows-x86_64"
                os.contains("mac") -> "darwin-x86_64"
                else -> "linux-x86_64"
            }
            val bin = ndk.resolve("toolchains/llvm/prebuilt/$host/bin")
            val suffix = if (host.startsWith("windows")) ".cmd" else ""
            return ABIS.flatMap { (triple, abi) ->
                val clang = bin.resolve("${abi.clang}$minSdk-clang$suffix").absolutePath
                val variable = triple.replace('-', '_')
                listOf(
                    "CARGO_TARGET_${variable.uppercase()}_LINKER" to clang,
                    "CC_$variable" to clang,
                    "AR_$variable" to bin.resolve("llvm-ar").absolutePath,
                )
            }.toMap()
        }
    }
}

/** The Rust crate of a `pageboard.android.rust` module. */
abstract class AndroidRustExtension {
    /** The directory of the crate (its `Cargo.toml`). */
    abstract val crate: DirectoryProperty

    /** The name of its library (`iroh_bridge` for `libiroh_bridge.so`). */
    abstract val library: Property<String>
}
