package io.github.valerioisufi.drawview.ml

import io.github.valerioisufi.drawview.ml.ModelInput.AUDIO
import io.github.valerioisufi.drawview.ml.ModelInput.IMAGE
import io.github.valerioisufi.drawview.ml.ModelInput.TEXT

/**
 * The models an application offers (Information Expert of their files). [Default] holds the
 * Gemma 4 models and EmbeddingGemma 2 of the LiteRT community on Hugging Face (Apache 2.0); an
 * application may give its own list.
 */
public class ModelCatalog(public val models: List<ModelSpec>) {
    init {
        require(models.distinctBy { it.id }.size == models.size) { "Two models of the catalog have the same id" }
    }

    /** The model named [id], if the catalog has it. */
    public operator fun get(id: String): ModelSpec? = models.firstOrNull { it.id == id }

    /** The models of [kind], in the order of the catalog. */
    public fun of(kind: ModelKind): List<ModelSpec> = models.filter { it.kind == kind }

    public companion object {
        /** Gemma 4 E2B: the phones and the tablets. */
        public val GEMMA_4_E2B: ModelSpec = ModelSpec(
            id = "gemma-4-e2b",
            name = "Gemma 4 E2B",
            kind = ModelKind.GENERATION,
            inputs = setOf(TEXT, IMAGE, AUDIO),
            fileName = "gemma-4-E2B-it.litertlm",
            url = huggingFace("gemma-4-E2B-it-litert-lm", "b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1", "gemma-4-E2B-it.litertlm"),
            sizeBytes = 2_588_147_712,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            memoryBytes = 2_600_000_000,
            maxImageTokens = 280
        )

        /** Gemma 4 E4B: the tablets and the computers. */
        public val GEMMA_4_E4B: ModelSpec = ModelSpec(
            id = "gemma-4-e4b",
            name = "Gemma 4 E4B",
            kind = ModelKind.GENERATION,
            inputs = setOf(TEXT, IMAGE, AUDIO),
            fileName = "gemma-4-E4B-it.litertlm",
            url = huggingFace("gemma-4-E4B-it-litert-lm", "2eee7ac325f20eb8c9ac1d0e972f7c84663062da", "gemma-4-E4B-it.litertlm"),
            sizeBytes = 3_659_530_240,
            sha256 = "0b2a8980ce155fd97673d8e820b4d29d9c7d99b8fa6806f425d969b145bd52e0",
            memoryBytes = 3_700_000_000,
            maxImageTokens = 280
        )

        /** Gemma 4 12B: the review of a lecture on a computer. */
        public val GEMMA_4_12B: ModelSpec = ModelSpec(
            id = "gemma-4-12b",
            name = "Gemma 4 12B",
            kind = ModelKind.GENERATION,
            inputs = setOf(TEXT, IMAGE, AUDIO),
            fileName = "gemma-4-12B-it.litertlm",
            url = huggingFace("gemma-4-12B-it-litert-lm", "7a0b1ce0ea821bcd01c5f72af84155e02191152f", "gemma-4-12B-it.litertlm"),
            sizeBytes = 6_883_278_368,
            sha256 = "58fd31b778ca2c21c80d634fb34fc5a89d11d563a38dfd3cbf1b40dbf252a8b6",
            memoryBytes = 7_000_000_000,
            maxImageTokens = 1120
        )

        /** EmbeddingGemma 2, its text encoder only (270M, int4): the search in the notes. */
        public val EMBEDDING_GEMMA_2_TEXT: ModelSpec = ModelSpec(
            id = "embeddinggemma-2-text",
            name = "EmbeddingGemma 2",
            kind = ModelKind.EMBEDDING,
            inputs = setOf(TEXT),
            fileName = "embeddinggemma-2-text-270m.litertlm",
            url = huggingFace(
                "embeddinggemma-2-text-270m-litert-lm",
                "9be6e8b90982095dc05c2bd162e4b954ee4dbac7",
                "embeddinggemma-2-text-270m.litertlm"
            ),
            sizeBytes = 164_626_432,
            sha256 = "2d079ee2f6f066b1f368e8d7c819f55214eaef1d0513b312321901f30ab286fb",
            memoryBytes = 340_000_000,
            dimensions = 768
        )

        /** The models of DrawView. */
        public val Default: ModelCatalog = ModelCatalog(listOf(GEMMA_4_E2B, GEMMA_4_E4B, GEMMA_4_12B, EMBEDDING_GEMMA_2_TEXT))

        private fun huggingFace(repository: String, revision: String, file: String): String =
            "https://huggingface.co/litert-community/$repository/resolve/$revision/$file"
    }
}
