plugins {
    id("pageboard.kmp.library")
    // The JNI library of the ink of Windows in the desktop jar
    id("pageboard.jvm.natives")
}

description = "The handwriting recognizers of Windows as the reader of handwriting of Pageboard on the desktop JVM"

// Windows only: the library calls the ink of Windows (C++/WinRT)
jvmNatives {
    platforms.set(listOf("windows-x86_64"))
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(project(":pageboard-handwriting"))
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmMain.dependencies {
            implementation(project(":pageboard-platform"))
        }
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
