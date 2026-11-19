package io.github.valerioisufi.drawview.ui.platform

import android.content.Context
import androidx.core.content.FileProvider

/**
 * The files the editor shares with the other applications (see `rememberFileSharer`), declared
 * in the manifest of `pageboard-ui` with the authority [authority]: a class of its own, so that it
 * does not clash with a `FileProvider` of the application.
 */
public class DrawViewFileProvider : FileProvider() {
    public companion object {
        /** The authority of the provider in the application of [context]. */
        public fun authority(context: Context): String = "${context.packageName}.drawview.files"
    }
}
