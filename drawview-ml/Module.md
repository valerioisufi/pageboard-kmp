# Module drawview-ml

The local models of DrawView, without the runtime that runs them: the models an application
offers (`ModelCatalog`, `ModelSpec`: Gemma 4 and EmbeddingGemma 2 by default), the ones on the
device (`InstalledModels`, `models/` of the data directory), their downloads, resumed and verified
by SHA-256 (`ModelDownloads`), and the models in memory (`LoadedModels`), loaded by their first
request and closed when they are no longer used.

A model runs through the port `ModelRuntime` (a `Generator` writes text from text, images and
audio; an `Embedder` turns texts into vectors), given by the application: LiteRT-LM in
`drawview-ml-litert`. Headless: no Compose; the state is `StateFlow`.
