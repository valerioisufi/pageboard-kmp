plugins {
    id("drawview.android.native")
}

description = "MicroTeX LaTeX engine of DrawView for Android (libtex.so), used by drawview-latex"

android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                targets += "tex"
            }
        }
    }
}
