package io.github.valerioisufi.drawview.ui.platform

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.browser.document
import org.khronos.webgl.ArrayBuffer
import org.khronos.webgl.Int8Array
import org.w3c.dom.HTMLInputElement
import org.w3c.files.FileReader
import org.w3c.files.get

@Composable
actual fun rememberFilePicker(type: PickerType, onPicked: (PickedFile) -> Unit): () -> Unit {
    val currentOnPicked by rememberUpdatedState(onPicked)
    return remember(type) {
        {
            val input = document.createElement("input") as HTMLInputElement
            input.type = "file"
            input.accept = if (type == PickerType.IMAGE) "image/*" else "application/pdf"
            input.onchange = {
                val file = input.files?.get(0)
                if (file != null) {
                    val reader = FileReader()
                    reader.onload = {
                        val buffer = reader.result.unsafeCast<ArrayBuffer>()
                        // A Kotlin/JS ByteArray is an Int8Array
                        currentOnPicked(PickedFile(file.name, Int8Array(buffer).unsafeCast<ByteArray>()))
                    }
                    reader.readAsArrayBuffer(file)
                }
            }
            input.click()
        }
    }
}

@Composable
actual fun HideSystemBarsEffect() {
}
