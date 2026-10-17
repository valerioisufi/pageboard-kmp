import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // The tests on a data directory of their own (build/test-data), never the one of the user
    id("drawview.test.data")
}

dependencies {
    implementation(project(":pencilnotes-shared"))
    implementation(project(":drawview-canvas"))
    implementation(project(":drawview-ui"))
    // Every pending change written before the application exits (DrawViewSessions)
    implementation(project(":drawview-session"))
    implementation(project(":drawview-lecture-ui"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.compose.uiToolingPreview)
}

compose.desktop {
    application {
        mainClass = "io.github.valerioisufi.pencilnotes.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "PencilNotes"
            packageVersion = "3.0.0"

            macOS {
                // Lecture assistant: recording of the lectures
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>PencilNotes records the lectures for the lecture assistant.</string>
                    """.trimIndent()
                }
            }
        }
    }
}
