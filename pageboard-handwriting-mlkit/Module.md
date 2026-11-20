# Module pageboard-handwriting-mlkit

ML Kit Digital Ink Recognition as a reader of handwriting of Pageboard, on Android only: 
`MlKitTextRecognizer` reads a line of samples with the line before as context and gives the
candidates of ML Kit; `MlKitLanguageModels` downloads, lists and deletes the models of the
languages (about 20 MB each, from Google, on Wi-Fi by default), only when the user asks. The
reading itself runs on the device.

On a device without Google Play services the models are not downloaded and the reader has no
language: nothing is read. On the other targets the module is empty.
