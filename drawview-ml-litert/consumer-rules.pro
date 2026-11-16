# The rules an application shrinking its code with R8 needs for drawview-ml-litert (published with
# its Android library).

# LiteRT-LM (litertlm-android 0.18.0) ships no keep rules, while its native library finds its Kotlin
# classes by name (InputData$Text, EmbeddingResponse...) and calls them back: with them renamed or
# removed, the first embedding aborts the application. Its classes keep their names and members.
-keep class com.google.ai.edge.litertlm.** { *; }
