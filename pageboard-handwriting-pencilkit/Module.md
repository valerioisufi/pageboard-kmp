# Module pageboard-handwriting-pencilkit

The handwriting recognizer of PencilKit (`PKStrokeRecognizer`) as a reader of handwriting of
Pageboard, on iOS, iPadOS and macOS 27 and later: `PencilKitTextRecognizer` gives a block of samples
to the recognizer of the system, which splits it into lines itself, and gives the most likely text
and every candidate, for the search. The recognition runs on the device, with the models of the
system: nothing is downloaded.

A Swift bridge (`native/bridge/pencilkit_bridge.swift`) behind a plain C API: a static library
through cinterop on iOS, a JNI library in the jar on macOS. The system reads only for an application
in the foreground, launched as an application: in the background, from a process started in a
terminal (a JVM run by Gradle), on the systems before 27, on Android and on the other desktops the
recognizer reads no language.
