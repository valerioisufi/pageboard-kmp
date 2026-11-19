plugins {
    id("pageboard.android.native")
}

description = "MicroTeX LaTeX engine of Pageboard for Android (libtex.so), used by pageboard-latex"

android {
    defaultConfig {
        externalNativeBuild {
            cmake {
                targets += "tex"
            }
        }
    }
}
