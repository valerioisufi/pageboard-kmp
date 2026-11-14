import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // The tests on a data directory of their own (build/test-data), never the one of the user
    id("drawview.test.data")
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "PencilNotesShared"
            isStatic = true
        }
    }

    jvm()

    android {
        namespace = "io.github.valerioisufi.pencilnotes.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
        // Strings and the title font are Compose resources (packaged as assets)
        androidResources {
            enable = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":drawview-ui"))
            implementation(project(":drawview-explorer"))
            // The documents in Room, in the data directory of the application
            implementation(project(":drawview-store-room"))
            implementation(project(":drawview-lecture-ui"))
            implementation(project(":drawview-sync-ui"))
            // The documents shared over the internet too (iroh), once the user turns it on
            implementation(project(":drawview-sync-iroh"))
            implementation(project(":drawview-export-ui"))
            implementation(project(":drawview-search-ui"))
            // The external models, where the application of a platform lets their clients in
            api(project(":drawview-agent"))
            implementation(project(":drawview-agent-ui"))
            implementation(project(":drawview-ml-litert"))
            // The engines of the PDF pages and of the formulas
            implementation(project(":drawview-pdf"))
            implementation(project(":drawview-latex"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.uiTest)
            // The main thread of the services, as in the application of the desktop
            implementation(libs.kotlinx.coroutinesSwing)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.valerioisufi.pencilnotes.generated.resources"
}
