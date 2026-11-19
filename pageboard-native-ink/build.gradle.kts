plugins {
    id("pageboard.android.native")
}

description = "Google Ink stroke modeller of DrawView for Android (libink_bridge.so), used by pageboard-ink"

android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                targets += "ink_bridge"
            }
        }
    }
}
