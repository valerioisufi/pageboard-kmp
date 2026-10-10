plugins {
    id("drawview.android.native")
}

description = "Google Ink stroke modeller of DrawView for Android (libink_bridge.so), used by drawview-ink"

android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                targets += "ink_bridge"
            }
        }
    }
}
