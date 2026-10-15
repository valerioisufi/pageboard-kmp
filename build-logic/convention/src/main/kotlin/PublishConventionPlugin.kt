import com.vanniktech.maven.publish.MavenPublishBaseExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure
import org.jetbrains.dokka.gradle.DokkaExtension

/**
 * A module published on Maven Central as `GROUP:<module name>:VERSION_NAME` (read from
 * `gradle.properties` by the plugin), with sources, its documentation (Dokka: the javadoc jar,
 * and `./gradlew :dokkaGenerate` for all the modules together; its `Module.md` as the page of the
 * module), a POM and, when a signing key is given (`signingInMemoryKey`), signatures.
 * `./gradlew publishToMavenLocal` publishes every module to the local repository.
 */
class PublishConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) = with(target) {
        // Before the publication: its javadoc jar is the documentation of Dokka
        pluginManager.apply("org.jetbrains.dokka")
        pluginManager.apply("com.vanniktech.maven.publish")

        val pomUrl = providers.gradleProperty("POM_URL").get()
        val scm = providers.gradleProperty("POM_SCM").get()
        val module = name
        extensions.configure<DokkaExtension> {
            moduleName.set(module)
            dokkaSourceSets.configureEach {
                val page = layout.projectDirectory.file("Module.md").asFile
                if (page.exists()) includes.from(page)
                sourceLink {
                    localDirectory.set(layout.projectDirectory.dir("src"))
                    remoteUrl("$pomUrl/blob/main/$module/src")
                    remoteLineSuffix.set("#L")
                }
            }
        }
        extensions.configure<MavenPublishBaseExtension> {
            publishToMavenCentral()
            if (providers.gradleProperty("signingInMemoryKey").isPresent) signAllPublications()
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
