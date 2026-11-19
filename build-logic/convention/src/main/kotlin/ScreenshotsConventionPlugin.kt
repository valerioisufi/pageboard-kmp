import io.github.takahirom.roborazzi.RoborazziExtension
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.testing.Test

/**
 * The screenshot tests of a module (Roborazzi): the reference images in its `screenshots/`, the
 * test JVM independent of the host, and the reference environment that records them
 * (`docs/plans/ci-screenshots.md`).
 *
 * The references are recorded and verified in one environment, the Linux runner of
 * `.github/workflows/screenshots.yml` (or its Docker image, `tools/screenshots/`), which sets
 * `DRAWVIEW_SCREENSHOTS=reference`. Elsewhere the glyphs are rasterized differently, so:
 * - `recordRoborazzi*` fails, unless `-Pdrawview.screenshots.recordHere=true` asks for images to
 *   look at, not to commit;
 * - `verifyRoborazzi*` compares as on the runner, after a notice that differences of antialiasing
 *   are expected.
 */
class ScreenshotsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("io.github.takahirom.roborazzi")
        extensions.configure(RoborazziExtension::class.java) {
            outputDir.set(layout.projectDirectory.dir("screenshots"))
        }

        // Dates, numbers and text drawn the same whatever the locale and the zone of the host
        tasks.withType(Test::class.java).configureEach {
            systemProperty("user.language", "en")
            systemProperty("user.country", "US")
            systemProperty("user.timezone", "UTC")
            systemProperty("file.encoding", "UTF-8")
        }

        val reference = providers.environmentVariable(ENVIRONMENT).orNull == REFERENCE
        if (reference) return@with
        val recordHere = providers.gradleProperty("drawview.screenshots.recordHere").orNull == "true"
        val recordProperty = providers.gradleProperty("roborazzi.test.record").orNull == "true"
        val verifyProperty = providers.gradleProperty("roborazzi.test.verify").orNull == "true"
        val path = path
        gradle.taskGraph.whenReady {
            val names = allTasks.filter { it.path.substringBeforeLast(':').ifEmpty { ":" } == path }.map { it.name }
            val recording = recordProperty || names.any { it.startsWith("recordRoborazzi") }
            val verifying = verifyProperty || names.any { it.startsWith("verifyRoborazzi") || it.startsWith("verifyAndRecordRoborazzi") }
            if (recording && !recordHere) {
                throw GradleException(
                    "$path: the reference screenshots are recorded on the Linux runner of the CI, not here. " +
                        "Run the workflow Screenshots on your branch (gh workflow run screenshots.yml -f branch=<branch>), " +
                        "or take the artifact screenshots-new of the failing check of your pull request, " +
                        "or record in the Docker image (tools/screenshots/run.sh record). " +
                        "-Pdrawview.screenshots.recordHere=true records here anyway, for images not to commit."
                )
            }
            if (verifying) {
                logger.warn(
                    "$path: the reference screenshots come from the Linux runner of the CI; on this system the " +
                        "antialiasing of the text differs, so screenshots may fail here without a change. " +
                        "compareRoborazziJvm writes the differences in build/outputs/roborazzi without failing; " +
                        "the check Screenshots of the pull request, or tools/screenshots/run.sh verify, is the one that counts."
                )
            }
        }
    }

    private companion object {
        /** The variable that marks the reference environment, and its value there. */
        const val ENVIRONMENT = "DRAWVIEW_SCREENSHOTS"
        const val REFERENCE = "reference"
    }
}
