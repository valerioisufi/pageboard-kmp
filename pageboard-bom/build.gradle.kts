plugins {
    `java-platform`
    id("pageboard.publish")
}

description = "The versions of the Pageboard libraries taken together: an application imports it and declares the libraries without a version"

// Every library of the repository at the version of this one. Each library depends on this
// platform too, so that Gradle takes all of them at the highest version an application asks for
dependencies {
    constraints {
        rootProject.subprojects
            .filter { it.name.startsWith("pageboard-") && it != project }
            .sortedBy { it.name }
            .forEach { api(project(it.path)) }
    }
}
