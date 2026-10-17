import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.Directory
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.testing.Test
import org.gradle.process.CommandLineArgumentProvider

/**
 * The data directory of the tests of a module: every [Test] task runs with `drawview.dataDir`
 * set to `build/test-data/<task>`, so that no test reads or writes the data of the user
 * (`~/.drawview`, `~/.pencilnotes`).
 *
 * The directory is emptied and created before the tests start: each run starts from no data, and
 * the data of a failed run stays to be looked at until the next one.
 */
class TestDataConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        tasks.withType(Test::class.java).configureEach {
            val directory = layout.buildDirectory.dir("test-data/$name")
            jvmArgumentProviders.add(TestDataDirectory(directory))
            doFirst {
                val folder = directory.get().asFile
                folder.deleteRecursively()
                folder.mkdirs()
            }
        }
    }
}

/**
 * The `drawview.dataDir` system property of the JVM of the tests. The directory is not an input:
 * its absolute path does not change the results, and the checkouts share them in the build cache.
 */
class TestDataDirectory(@get:Internal val directory: Provider<Directory>) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Ddrawview.dataDir=${directory.get().asFile.absolutePath}")
}
