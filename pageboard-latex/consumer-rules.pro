# The rules an application shrinking its code with R8 needs for pageboard-latex (published with its Android
# library).

# The classes with native methods keep their names and those methods: the functions of the native
# library are named after them.
-keepclasseswithmembernames,includedescriptorclasses class io.nano.tex.** {
    native <methods>;
}

# MicroTeX registers the native methods of these classes by name (RegisterNatives in
# native/tex/port/jni_help.cpp): every one of them must stay, even those never called from Kotlin,
# or the registration fails and the application aborts.
-keepclasseswithmembers class io.nano.tex.LaTeX {
    native <methods>;
}
-keepclasseswithmembers class io.nano.tex.TeXRender {
    native <methods>;
}

# The classes MicroTeX finds by name (native/tex/port/jni_def.cpp): their fields and methods are
# read and called from the native code.
-keep class io.nano.tex.Rect { *; }
-keep class io.nano.tex.TextLayout { *; }
-keep class io.nano.tex.Font { *; }
-keep class io.nano.tex.ActionRecorder { *; }
