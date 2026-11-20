# Module pageboard-handwriting

Handwriting recognition for Pageboard, without the engines that read: the strokes of a block as
they were written (`InkSample`, made of `StrokeElement`s or of the `StrokeData` a store reads
without modeling them), the ports of the readers (`TextInkRecognizer` for text, `MathInkRecognizer`
for formulas, `InkLanguageModels` for the models of the languages), and their readings
(`InkReading`: the most likely text, the others, and a text for the search).

`InkLines` splits a block into lines and leaves out what looks like a drawing; `HandwritingReading`
gives each line to the reader of its language, with the line before as context, and puts the
readings together. The engines are given by the application in `HandwritingEngines`: ML Kit on
Android (`pageboard-handwriting-mlkit`), PencilKit on the Apple systems 27, the ink of Windows; with
none, nothing is read. `InkMl` writes and reads samples as InkML (W3C), the format of the benches.

Headless: no Compose. See `docs/plans/handwriting.md`.
