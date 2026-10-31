import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

/**
 * The benchmarks of a module: the test classes whose name ends with `Benchmark` (see
 * `docs/performance.md`). They are left out of the tests, and run alone with
 * `-Pdrawview.benchmarks=true`, every time (their results are what they print).
 */
class BenchmarksConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        val benchmarks = providers.gradleProperty("drawview.benchmarks").map { it.toBoolean() }.orElse(false).get()
        tasks.withType(Test::class.java).configureEach {
            if (benchmarks) {
                filter {
                    includeTestsMatching("*Benchmark")
                    // A module without benchmarks has nothing to run
                    isFailOnNoMatchingTests = false
                }
                testLogging { showStandardStreams = true }
                outputs.upToDateWhen { false }
                // Large documents, loaded several times
                maxHeapSize = "4g"
            } else {
                filter { excludeTestsMatching("*Benchmark") }
            }
        }
    }
}
