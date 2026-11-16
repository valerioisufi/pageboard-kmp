import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
    // The tests on a data directory of their own (build/test-data), never the one of the user
    id("drawview.test.data")
    // The release signed with the upload key of Google Play, when this machine has it
    id("drawview.android.signing")
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":pencilnotes-shared"))
    // The commands of the screen shown, for the list of the shortcuts of the system
    implementation(project(":drawview-ui"))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "io.github.valerioisufi.pencilnotes"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        // The application on Google Play since its first version (the original Android app); the
        // debug build keeps the id below
        applicationId = "com.studiomath.pencilnotes"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // The original Android app ended at versionCode 3 / versionName 2.1
        versionCode = 4
        versionName = "3.0"
        // The ABIs the native engines are built for: the libraries of androidx bring x86 too,
        // which would make an x86 split without the engines
        ndk {
            abiFilters += ANDROID_ABIS
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        release {
            // R8 shrinks the code and the resources; the libraries give the rules of their native code
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// The debug build stays the application the tablets already have, with their notes
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("io.github.valerioisufi.pencilnotes")
    }
}
