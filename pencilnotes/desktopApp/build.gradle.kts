import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    // The tests on a data directory of their own (build/test-data), never the one of the user
    id("pageboard.test.data")
}

dependencies {
    implementation(project(":pencilnotes-shared"))
    implementation(project(":pageboard-ui"))
    // Every pending change written before the application exits (PageboardSessions)
    implementation(project(":pageboard-session"))
    implementation(project(":pageboard-lecture-ui"))

    implementation(compose.desktop.currentOs)
    implementation(libs.kotlinx.coroutinesSwing)

    // The clients of the external models: the socket and the commands (docs/plans/agent-tools.md)
    implementation(project(":pageboard-agent"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
}

compose.desktop {
    application {
        mainClass = "io.github.valerioisufi.pencilnotes.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "PencilNotes"
            packageVersion = "3.0.0"

            macOS {
                iconFile.set(project.file("src/main/resources/icons/icon.icns"))
                entitlementsFile.set(project.file("entitlements.plist"))
                runtimeEntitlementsFile.set(project.file("entitlements.plist"))
                // Lecture assistant: recording of the lectures
                infoPlist {
                    extraKeysRawXml = """
                        <key>NSMicrophoneUsageDescription</key>
                        <string>PencilNotes records the lectures for the lecture assistant.</string>
                    """.trimIndent()
                }
            }
            windows {
                iconFile.set(project.file("src/main/resources/icons/icon.ico"))
            }
            linux {
                iconFile.set(project.file("src/main/resources/icons/icon.png"))
            }
        }
    }
}
