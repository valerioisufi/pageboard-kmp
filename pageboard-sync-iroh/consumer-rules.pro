# The rules an application shrinking its code with R8 needs for pageboard-sync-iroh (published with its Android
# library).

# The classes with native methods keep their names and those methods: the functions of the native
# library are named after them.
-keepclasseswithmembernames,includedescriptorclasses class io.github.valerioisufi.pageboard.sync.iroh.** {
    native <methods>;
}
