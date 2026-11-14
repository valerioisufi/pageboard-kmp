plugins {
    id("drawview.kmp.library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidxRoom)
}

description = "The Room (SQLite) store of DrawView documents, folders, operations and preferences"

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-store"))
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.androidx.room.runtime)
            implementation(libs.androidx.sqlite.bundled)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(project(":drawview-store-testing"))
            // The strokes loaded modeled as the editor draws them
            implementation(project(":drawview-ink"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmTest.dependencies {
            // The migrations tested on databases of the versions of the schemas
            implementation(libs.androidx.room.testing)
        }
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    add("kspJvm", libs.androidx.room.compiler)
    add("kspAndroid", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}
