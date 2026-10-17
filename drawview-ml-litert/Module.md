# Module drawview-ml-litert

LiteRT-LM as the `ModelRuntime` of `drawview-ml`: `LiteRtRuntime` loads the `.litertlm` models
(Gemma 4, EmbeddingGemma 2) on the GPU when it can be used, on the processor otherwise, and answers
in the JSON of a schema when a request gives one (constrained decoding).

On the desktop JVM it runs on Windows x86-64, macOS on Apple silicon and Linux (x86-64, arm64),
the native libraries of `litertlm-jvm`; on Android and iOS it supports no model yet
(`LiteRtRuntime.isAvailable` is false), and the applications use their other models. Headless: no
Compose.
