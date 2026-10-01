@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState

@Composable
actual fun rememberFilePicker(type: PickerType, onPicked: (PickedFile) -> Unit): () -> Unit {
    val currentOnPicked by rememberUpdatedState(onPicked)
    return remember(type) {
        {
            openFileInput(if (type == PickerType.IMAGE) "image/*" else "application/pdf") { name, binary ->
                // One char per byte (latin1): the copy happens on the Kotlin side
                val bytes = ByteArray(binary.length) { binary[it].code.toByte() }
                currentOnPicked(PickedFile(name, bytes))
            }
        }
    }
}

/**
 * Shows the file chooser of the browser and reads the selected file, passing its content to
 * [onLoaded] as a binary (latin1) string.
 */
private fun openFileInput(accept: String, onLoaded: (String, String) -> Unit): Unit = js(
    """{
    const input = document.createElement('input');
    input.type = 'file';
    input.accept = accept;
    input.onchange = () => {
        const file = input.files && input.files[0];
        if (!file) return;
        const reader = new FileReader();
        reader.onload = () => {
            const bytes = new Uint8Array(reader.result);
            let binary = '';
            for (let i = 0; i < bytes.length; i += 0x8000) {
                binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
            }
            onLoaded(file.name, binary);
        };
        reader.readAsArrayBuffer(file);
    };
    input.click();
}"""
)

@Composable
actual fun HideSystemBarsEffect() {
}
