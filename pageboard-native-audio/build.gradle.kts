import java.io.StringReader
import java.util.Properties

plugins {
    id("pageboard.android.native")
}

description = "Audio codecs of DrawView for Android, libopus and vo-aacenc (libaudio_bridge.so), used by pageboard-lecture"

/** The version of the source of an audio codec (native/<name>/<name>.properties). */
fun codecVersion(name: String): String = Properties().apply {
    load(StringReader(rootProject.file("native/$name/$name.properties").readText()))
}.getProperty("version")

android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                val sources = rootProject.layout.buildDirectory
                arguments += listOf(
                    "-DPAGEBOARD_OPUS_DIR=${sources.dir("opus/src/opus-${codecVersion("opus")}").get().asFile.absolutePath}",
                    "-DPAGEBOARD_AAC_DIR=${sources.dir("aac/src/vo-aacenc-${codecVersion("aac")}").get().asFile.absolutePath}"
                )
                targets += "audio_bridge"
            }
        }
    }
}

// The sources of the codecs must be extracted before CMake configures the project
tasks.configureEach {
    if (name.startsWith("configureCMake")) dependsOn(":extractOpusSource", ":extractAacSource")
}
