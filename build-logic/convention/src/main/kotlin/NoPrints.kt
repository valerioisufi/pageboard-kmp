import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.SkipWhenEmpty
import org.gradle.api.tasks.TaskAction

/**
 * Checks that the main sources of a library print nothing (GRASP Protected Variations): what a
 * library tells goes to the log of the platform (`PageboardLog`), which the application routes,
 * never to the standard output of the application.
 */
@CacheableTask
abstract class CheckNoPrints : DefaultTask() {
    /** The name of the library, for the message. */
    @get:Input
    abstract val module: Property<String>

    /** The Kotlin files of the main source sets. */
    @get:InputFiles
    @get:SkipWhenEmpty
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** The lines that print (empty when the check passes). */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val found = sources.files.sorted().flatMap { file ->
            file.readLines().withIndex()
                .filter { (_, line) -> line.trim().let { code -> !code.startsWith("//") && !code.startsWith("*") && PRINT.containsMatchIn(code) } }
                .map { (index, _) -> "${file.path}:${index + 1}" }
        }
        report.get().asFile.writeText(found.joinToString("\n"))
        if (found.isNotEmpty()) {
            throw GradleException(
                "${module.get()} prints (PageboardLog.warn writes to the log of the platform):\n" +
                    found.joinToString("\n") { "  $it" }
            )
        }
    }

    private companion object {
        val PRINT = Regex("""\bprintln\(|\bSystem\.(out|err)\b""")
    }
}

/** Registers `checkNoPrints` (part of `check`) for the main sources of a library. */
internal fun Project.checkNoPrints() {
    val check = tasks.register("checkNoPrints", CheckNoPrints::class.java) {
        group = "verification"
        description = "Checks that the main sources of the library print nothing"
        module.set(project.name)
        sources.from(fileTree("src") { include("*Main/**/*.kt") })
        report.set(layout.buildDirectory.file("reports/no-prints/found.txt"))
    }
    tasks.named("check") { dependsOn(check) }
}
