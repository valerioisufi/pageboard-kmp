import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.named
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType
import java.io.File
import java.util.zip.ZipFile

/**
 * Checks that every type named by the public API of a library comes with what the library
 * declares `api` (GRASP Protected Variations): a type of a dependency given as `implementation`
 * would be in the signatures an application calls without being on its compile classpath.
 *
 * The API is the JVM dump of the library (`api/jvm/<module>.api`); the types of the library itself,
 * of Kotlin and of the JDK are left out, and every other one must be a class of the jars of the
 * `api` dependencies, with what they bring the same way (their `jvmApiElements`).
 */
@CacheableTask
abstract class CheckApiDependencies : DefaultTask() {
    /** The name of the library, for the message. */
    @get:Input
    abstract val module: Property<String>

    /** The JVM dump of the public API. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dump: RegularFileProperty

    /** The classes of the library. */
    @get:Classpath
    abstract val classes: ConfigurableFileCollection

    /** What the library declares `api`, and what those bring as `api` in turn. */
    @get:Classpath
    abstract val apiClasspath: ConfigurableFileCollection

    /** The types of the API that no `api` dependency gives (empty when the check passes). */
    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val named = TYPE.findAll(dump.get().asFile.readText()).map { it.groupValues[1] }.toSortedSet()
        val own = classNames(classes.files)
        val given = classNames(apiClasspath.files)
        val missing = named.filter { type -> PLATFORM.none { type.startsWith(it) } && type !in own && type !in given }
        report.get().asFile.writeText(missing.joinToString("\n"))
        if (missing.isNotEmpty()) {
            val packages = missing.groupBy { it.substringBeforeLast('/').replace('/', '.') }
                .entries.joinToString("\n") { (pkg, types) -> "  $pkg: ${types.joinToString { it.substringAfterLast('/') }}" }
            throw GradleException(
                "The public API of ${module.get()} names types that no `api` dependency gives: declare their " +
                    "libraries with api(...), or keep the types out of the API\n$packages"
            )
        }
    }

    /** The names (`a/b/C$D`) of the classes in [files], directories or jars. */
    private fun classNames(files: Set<File>): Set<String> = buildSet {
        for (file in files) {
            when {
                file.isDirectory -> file.walkTopDown()
                    .filter { it.isFile && it.name.endsWith(".class") }
                    .forEach { add(it.relativeTo(file).invariantSeparatorsPath.removeSuffix(".class")) }
                file.isFile && file.name.endsWith(".jar") -> ZipFile(file).use { zip ->
                    zip.entries().asSequence().map { it.name }
                        .filter { it.endsWith(".class") && !it.startsWith("META-INF/") }
                        .forEach { add(it.removeSuffix(".class")) }
                }
            }
        }
    }

    private companion object {
        /** A type in a JVM descriptor: `Lpackage/Class$Inner;`. */
        val TYPE = Regex("L([A-Za-z_][\\w/$]*);")

        /** The types of the JDK and of Kotlin, always there. */
        val PLATFORM = listOf("java/", "javax/", "jdk/", "sun/", "kotlin/", "org/w3c/", "org/xml/")
    }
}

/** Registers `checkApiDependencies` (part of `check`) for the JVM target of a library. */
internal fun Project.checkApiDependencies() {
    val kotlin = extensions.getByType(KotlinMultiplatformExtension::class.java)
    val api = configurations.create("pageboardApiClasspath") {
        isCanBeConsumed = false
        isCanBeResolved = true
        description = "What the library declares api, resolved for the JVM (checkApiDependencies)"
        extendsFrom(configurations.getByName("commonMainApi"), configurations.getByName("jvmMainApi"))
        attributes {
            attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage.JAVA_API))
            attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category.LIBRARY))
            attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements.JAR))
            attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE, objects.named(TargetJvmEnvironment.STANDARD_JVM))
            attribute(KotlinPlatformType.attribute, KotlinPlatformType.jvm)
        }
    }
    val check = tasks.register("checkApiDependencies", CheckApiDependencies::class.java) {
        group = "verification"
        description = "Checks that the types of the public API come with the api dependencies"
        module.set(project.name)
        dump.set(layout.projectDirectory.file("api/jvm/${project.name}.api"))
        classes.from(kotlin.jvm().compilations.getByName("main").output.classesDirs)
        apiClasspath.from(api)
        report.set(layout.buildDirectory.file("reports/api-dependencies/missing.txt"))
        // The dump read is the one recorded, when both run
        mustRunAfter("updateKotlinAbi")
    }
    tasks.named("check") { dependsOn(check) }
}
