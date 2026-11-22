# Module pageboard-pdf

PDF documents read, drawn and written by PDFium (desktop, Android with `pageboard-native-pdfium`,
iOS with the PDFium framework embedded by the application): `PdfiumEngine` opens them, gives the
editor its PDF pages (`PdfiumEngine::createTileAdapter` in `PageboardEngines`) and the text of
their characters; `PdfWriter` writes PDFs (the export).

## On iOS

PDFium is a dynamic framework that the application embeds, of the same version as the library:
`PDFium.xcframework.zip` of the release of GitHub (`pageboard-v<version>`, with its SHA-256 beside
it). The klib links it by name (`-framework PDFium`), so an iOS application with `pageboard-pdf`, or
with `pageboard-export` or `-export-ui` which take it, does not link without it:

* in Xcode, `PDFium.xcframework` in *Frameworks, Libraries, and Embedded Content* of the
  application, *Embed & Sign*; with a static Kotlin framework, `-lc++` in *Other Linker Flags* too
  (the C++ engines inside the klibs);
* a dynamic Kotlin framework, which Gradle links, gives the folder of the slice of its target:

```kotlin
iosSimulatorArm64 {
    binaries.framework {
        isStatic = false
        linkerOpts("-F", "path/to/PDFium.xcframework/ios-arm64-simulator")
    }
}
```
