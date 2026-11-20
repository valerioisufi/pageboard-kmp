# Module pageboard-handwriting-windows

The handwriting recognizers of Windows (`Windows.UI.Input.Inking.InkRecognizerContainer`) as a
reader of handwriting of Pageboard, on the desktop JVM: `WindowsInkRecognizer` reads a line of
samples with the recognizer of its language, installed with the languages of Windows (Settings,
Time & language), and gives the candidates of each word. A JNI library (C++/WinRT) in the jar, for
Windows on x86-64; on the other systems, or without a recognizer of the language, nothing is read.
