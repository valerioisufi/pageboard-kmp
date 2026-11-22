# The rules an application shrinking its code with R8 needs for pageboard-latex (published with its Android
# library).

# The classes with native methods keep their names and those methods: the functions of the native
# library are named after them.
-keepclasseswithmembernames,includedescriptorclasses class io.github.valerioisufi.pageboard.latex.microtex.** {
    native <methods>;
}

# MicroTeX registers the native methods of these classes by name (RegisterNatives in
# native/tex/port/jni_help.cpp): every one of them must stay, even those never called from Kotlin,
# or the registration fails and the application aborts.
-keepclasseswithmembers class io.github.valerioisufi.pageboard.latex.microtex.LaTeX {
    native <methods>;
}
-keepclasseswithmembers class io.github.valerioisufi.pageboard.latex.microtex.TeXRender {
    native <methods>;
}

# The classes MicroTeX finds by name (native/tex/port/jni_def.cpp): their fields and methods are
# read and called from the native code.
-keep class io.github.valerioisufi.pageboard.latex.microtex.Rect { *; }
-keep class io.github.valerioisufi.pageboard.latex.microtex.TextLayout { *; }
-keep class io.github.valerioisufi.pageboard.latex.microtex.Font { *; }
-keep class io.github.valerioisufi.pageboard.latex.microtex.ActionRecorder { *; }
