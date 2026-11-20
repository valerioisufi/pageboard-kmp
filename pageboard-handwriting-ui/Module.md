# Module pageboard-handwriting-ui

Handwriting converted into text in the Pageboard editor. `HandwritingConversion` reads the strokes
selected with the readers of the device and makes the text box that replaces them: at their place,
as wide as they are, its letters as high as the handwritten ones, in their color; the replacement
is one change to undo. `handwritingEditorExtension` adds "Convert to text" to the context menu of a
selection of strokes, offered when the device reads the language, and a sheet with the reading to
edit and the other readings before the strokes are replaced.

The formulas are not converted yet: no reader of formulas ships (see `docs/plans/handwriting.md`).
