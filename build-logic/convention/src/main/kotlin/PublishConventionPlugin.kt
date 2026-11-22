import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.MavenPublishBaseExtension
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.dokka.gradle.DokkaExtension

/**
 * A module published on Maven Central as `GROUP:<module name>:VERSION_NAME` (read from
 * `gradle.properties` by the plugin), with sources, an empty javadoc jar (the documentation is the
 * one of Dokka on GitHub Pages: `./gradlew :dokkaGenerate` for all the modules together, the
 * `Module.md` of each one as its page), a POM and, when a signing key is given
 * (`signingInMemoryKey`), signatures: a release (a version without `-SNAPSHOT`) is not published to
 * Maven Central without them. `./gradlew publishToMavenLocal` publishes every module to the local
 * repository.
 */
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        pluginManager.apply("org.jetbrains.dokka")
        pluginManager.apply("com.vanniktech.maven.publish")

        val pomUrl = providers.gradleProperty("POM_URL").get()
        val scm = providers.gradleProperty("POM_SCM").get()
        val version = providers.gradleProperty("VERSION_NAME").get()
        val release = !version.endsWith("-SNAPSHOT")
        val module = name
        extensions.configure<DokkaExtension> {
            moduleName.set(module)
            dokkaSourceSets.configureEach {
                val page = layout.projectDirectory.file("Module.md").asFile
                if (page.exists()) includes.from(page)
                sourceLink {
                    localDirectory.set(layout.projectDirectory.dir("src"))
                    // The sources of the version documented: its tag, or main for a snapshot
                    remoteUrl("$pomUrl/blob/${if (release) "pageboard-v$version" else "main"}/$module/src")
                    remoteLineSuffix.set("#L")
                }
            }
        }
        extensions.configure<MavenPublishBaseExtension> {
            // The platform of the module (Kotlin Multiplatform, an Android library, the BOM), with
            // its sources and an empty javadoc: the plugins of the platform are applied before this one
            configureBasedOnAppliedPlugins(JavadocJar.Empty(), SourcesJar.Sources())
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) {
                signAllPublications()
            } else if (release) {
                // The Portal refuses a release without its signatures, once uploaded: it stops here
                tasks.matching { it.name.contains("MavenCentral") }.configureEach {
                    doFirst {
                        throw GradleException("$module $version is a release: it is published to Maven Central signed (signingInMemoryKey)")
                    }
                }
            }
            pom {
                name.set(project.name)
                description.set(provider { project.description ?: project.name })
                inceptionYear.set("2025")
                url.set(pomUrl)
                licenses {
                    license {
                        name.set("MIT License")
                        url.set("https://opensource.org/licenses/MIT")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("valerioisufi")
                        name.set("Valerio Isufi")
                        url.set("https://github.com/valerioisufi")
                    }
                }
                scm {
                    url.set(pomUrl)
                    connection.set("scm:git:https://$scm.git")
                    developerConnection.set("scm:git:ssh://git@$scm.git")
                }
            }
        }
    }
}
