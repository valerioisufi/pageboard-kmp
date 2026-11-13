plugins {
    id("drawview.kmp.library")
    // The JNI library of Google Ink in the desktop jar
    id("drawview.jvm.natives")
}

description = "Google Ink stroke modeller of DrawView: the strokes as the editor draws them, native on every platform"

kotlin {
    applyDefaultHierarchyTemplate {
        common {
            // The JNI code of the JVM and of Android, the same on both
            group("jvmAndAndroid") {
                withJvm()
                withCompilations { it.platformType == org.jetbrains.kotlin.gradle.plugin.KotlinPlatformType.androidJvm }
            }
        }
    }

    val nativeDir = rootProject.layout.projectDirectory.dir("native")
    listOf(
        iosArm64() to "iphoneos",
        iosSimulatorArm64() to "iphonesimulator"
    ).forEach { (iosTarget, sdk) ->
        // Google Ink C++ engine (native/scripts/build_ios.sh)
        val inkBridge = iosTarget.compilations.getByName("main").cinterops.create("inkbridge") {
            definitionFile.set(project.file("src/nativeInterop/cinterop/inkbridge.def"))
            includeDirs(nativeDir.dir("bridge"))
            extraOpts("-libraryPath", nativeDir.dir("build/ios/$sdk/lib").asFile.absolutePath)
        }
        tasks.named(inkBridge.interopProcessingTaskName) {
            dependsOn(":buildIosNativeLibs-$sdk")
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":drawview-model"))
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        androidMain.dependencies {
            // libink_bridge.so built with the NDK
            if (findProject(":drawview-native-ink") != null) {
                implementation(project(":drawview-native-ink"))
            }
        }
    }
}
