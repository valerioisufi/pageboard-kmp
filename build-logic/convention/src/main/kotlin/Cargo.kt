import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.MapProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.util.Properties
import javax.inject.Inject

/**
 * The cargo of this machine: `rust.cargo` in `local.properties`, else `~/.cargo/bin`, else the
 * `PATH` (Gradle started by Android Studio does not see the `PATH` of the shell).
 */
fun Project.cargoExecutable(): String {
    val local = rootProject.file("local.properties")
    if (local.isFile) {
        val configured = Properties().apply { local.inputStream().use(::load) }.getProperty("rust.cargo")
        if (!configured.isNullOrBlank()) return configured
    }
    val name = if (System.getProperty("os.name").lowercase().startsWith("windows")) "cargo.exe" else "cargo"
    val candidates = listOf(File(System.getProperty("user.home"), ".cargo/bin/$name")) +
        System.getenv("PATH").orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }.map { File(it, name) }
    return candidates.firstOrNull { it.canExecute() }?.absolutePath ?: name
}

/** The `PATH` for [cargo]: its directory first, so that it finds `rustc` beside it (rustup). */
fun cargoSearchPath(cargo: String): String =
    listOfNotNull(File(cargo).parentFile?.path, System.getenv("PATH")).joinToString(File.pathSeparator)

/** The Rust crates of DrawView build here (`native/build/rust`), whatever module asks. */
val Project.rustTargetDirectory: File
    get() = rootProject.layout.projectDirectory.dir("native/build/rust").asFile

/**
 * Builds the library of a Rust crate with cargo, for each of [targets] (target triple → folder),
 * into [output]`/<folder>/`: a `cdylib` (Android, desktop) or a `staticlib` (iOS), with the
 * versions of `Cargo.lock` (`--locked`).
 */
abstract class CargoBuild : DefaultTask() {
    /** The sources of the crate (what makes the library change). */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The directory of the crate (its `Cargo.toml`). */
    @get:Internal
    abstract val crate: DirectoryProperty

    /** The name of the library (`iroh_bridge` for `libiroh_bridge.so`). */
    @get:Input
    abstract val library: Property<String>

    @get:Input
    abstract val cargo: Property<String>

    /** `cdylib` or `staticlib`. */
    @get:Input
    abstract val crateType: Property<String>

    @get:Input
    abstract val targets: MapProperty<String, String>

    /** The environment of cargo (linkers, minimum versions of the systems). */
    @get:Input
    abstract val environment: MapProperty<String, String>

    /** Whether the debug symbols leave the library (the static libraries hold those of `std`). */
    @get:Input
    abstract val stripDebugSymbols: Property<Boolean>

    @get:Internal
    abstract val targetDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val exec: ExecOperations

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    init {
        stripDebugSymbols.convention(false)
    }

    @TaskAction
    fun build() {
        fileSystem.delete { delete(output) }
        for ((triple, folder) in targets.get()) {
            exec.exec {
                workingDir(crate.get().asFile)
                environment(this@CargoBuild.environment.get())
                environment("PATH", cargoSearchPath(cargo.get()))
                environment("CARGO_TARGET_DIR", targetDirectory.get().asFile.absolutePath)
                commandLine(
                    cargo.get(), "rustc", "--release", "--locked", "--lib",
                    "--crate-type", crateType.get(), "--target", triple
                )
            }
            val name = fileName(triple)
            val built = targetDirectory.get().file("$triple/release/$name").asFile
            val destination = output.get().dir(folder).asFile.apply { mkdirs() }
            if (stripDebugSymbols.get()) {
                exec.exec { commandLine("strip", "-S", "-o", File(destination, name).absolutePath, built.absolutePath) }
            } else {
                fileSystem.copy {
                    from(built)
                    into(destination)
                }
            }
        }
    }

    private fun fileName(triple: String): String {
        val name = library.get()
        val static = crateType.get() == "staticlib"
        return when {
            "windows" in triple -> if (static) "$name.lib" else "$name.dll"
            "apple" in triple -> if (static) "lib$name.a" else "lib$name.dylib"
            else -> if (static) "lib$name.a" else "lib$name.so"
        }
    }
}
