import org.gradle.api.DefaultTask
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.TaskProvider
import javax.inject.Inject

/**
 * The licenses every artifact of DrawView carries (`META-INF/drawview/<module>/`): the license of
 * DrawView and the notices of the third-party code and fonts (`THIRD_PARTY_LICENSES.md`, a
 * superset of what each artifact contains), as resources of its JVM and Android parts.
 */
abstract class LicensesTask : DefaultTask() {
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val licenses: ConfigurableFileCollection

    @get:Input
    abstract val module: Property<String>

    @get:OutputDirectory
    abstract val output: DirectoryProperty

    @get:Inject
    abstract val fileSystem: FileSystemOperations

    @TaskAction
    fun copy() {
        fileSystem.sync {
            from(licenses)
            into(output.dir("META-INF/drawview/${module.get()}"))
        }
    }
}

/** The task writing the licenses of this module (once per module). */
internal fun Project.licensesTask(): TaskProvider<LicensesTask> =
    if ("drawviewLicenses" in tasks.names) tasks.named("drawviewLicenses", LicensesTask::class.java)
    else tasks.register("drawviewLicenses", LicensesTask::class.java) {
        licenses.from(rootProject.file("LICENSE"), rootProject.file("THIRD_PARTY_LICENSES.md"))
        module.set(project.name)
        output.set(layout.buildDirectory.dir("generated/drawview-licenses"))
    }
