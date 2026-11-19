package io.github.valerioisufi.pageboard.ml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

class LoadedModelsTest {
    private val first = InstalledModel(testSpec(ByteArray(1), id = "first"), "/models/first")
    private val second = InstalledModel(testSpec(ByteArray(2), id = "second"), "/models/second")
    private val embedding = InstalledModel(testSpec(ByteArray(3), id = "embedding", kind = ModelKind.EMBEDDING), "/models/embedding")

    /** A runtime that says what it loads and closes. */
    private class FakeRuntime : ModelRuntime {
        val events = mutableListOf<String>()
        var failing = false
        var running = 0
        var mostRunning = 0

        override fun supports(spec: ModelSpec): Boolean = true

        override suspend fun generator(model: InstalledModel, options: GeneratorOptions): Generator {
            if (failing) throw ModelException("cannot load")
            events.add("load ${model.spec.id}")
            return object : Generator {
                override val model = model
                override val accelerator = Accelerator.CPU

                override suspend fun generate(request: GenerationRequest, onProgress: (Int) -> Unit): String {
                    mostRunning = maxOf(mostRunning, ++running)
                    delay(100)
                    running--
                    return model.spec.id
                }

                override fun close() {
                    events.add("close ${model.spec.id}")
                }
            }
        }

        override suspend fun embedder(model: InstalledModel, options: EmbedderOptions): Embedder {
            events.add("load ${model.spec.id}")
            return object : Embedder {
                override val model = model
                override val accelerator = Accelerator.CPU
                override val dimensions = 2

                override suspend fun embed(texts: List<String>): List<FloatArray> = texts.map { floatArrayOf(1f, 0f) }

                override fun close() {
                    events.add("close ${model.spec.id}")
                }
            }
        }
    }

    private val request = GenerationRequest(system = "", parts = emptyList())

    @Test
    fun aModelKeptLoadedAnswersAgainWithoutLoadingAndIsClosedAfterItsTime() = runTest {
        val runtime = FakeRuntime()
        val models = LoadedModels(runtime, scope = backgroundScope)
        repeat(2) { models.withGenerator(first, keepLoadedMillis = 10_000) { it.generate(request) } }
        assertEquals(listOf("load first"), runtime.events)
        assertEquals(setOf("first"), models.loaded.value)

        advanceTimeBy(10_001)
        runCurrent()
        assertEquals(listOf("load first", "close first"), runtime.events)
        assertEquals(emptySet(), models.loaded.value)
    }

    @Test
    fun aModelNotKeptIsClosedAfterItsRequest() = runTest {
        val runtime = FakeRuntime()
        val models = LoadedModels(runtime, scope = backgroundScope)
        assertEquals("first", models.withGenerator(first) { it.generate(request) })
        assertEquals(listOf("load first", "close first"), runtime.events)
    }

    @Test
    fun aNewModelTakesThePlaceOfTheOneUsedLeastRecently() = runTest {
        val runtime = FakeRuntime()
        val models = LoadedModels(runtime, maxGenerators = 1, scope = backgroundScope)
        models.withGenerator(first, keepLoadedMillis = 60_000) { it.generate(request) }
        models.withEmbedder(embedding, keepLoadedMillis = 60_000) { it.embed(listOf("a")) }
        models.withGenerator(second, keepLoadedMillis = 60_000) { it.generate(request) }

        // The embedder has a place of its own
        assertEquals(listOf("load first", "load embedding", "close first", "load second"), runtime.events)
        assertEquals(setOf("embedding", "second"), models.loaded.value)
    }

    @Test
    fun aModelAnswersOneRequestAtATime() = runTest {
        val runtime = FakeRuntime()
        val models = LoadedModels(runtime, scope = backgroundScope)
        val jobs = List(3) { launch { models.withGenerator(first, keepLoadedMillis = 1_000) { it.generate(request) } } }
        jobs.forEach { it.join() }
        assertEquals(1, runtime.mostRunning)
        assertEquals(listOf("load first"), runtime.events)
    }

    @Test
    fun aModelUnloadedIsClosedAndLoadedAgainByTheNextRequest() = runTest {
        val runtime = FakeRuntime()
        val models = LoadedModels(runtime, scope = backgroundScope)
        models.withGenerator(first, keepLoadedMillis = 60_000) { it.generate(request) }
        models.unload("first")
        assertEquals(emptySet(), models.loaded.value)
        models.withGenerator(first, keepLoadedMillis = 60_000) { it.generate(request) }
        models.unloadAll()
        assertEquals(listOf("load first", "close first", "load first", "close first"), runtime.events)
    }

    @Test
    fun aModelThatCannotBeLoadedLeavesNothingLoaded() = runTest {
        val runtime = FakeRuntime().apply { failing = true }
        val models = LoadedModels(runtime, scope = backgroundScope)
        assertFailsWith<ModelException> { models.withGenerator(first) { it.generate(request) } }
        assertEquals(emptySet(), models.loaded.value)

        // The place is free for the next one
        runtime.failing = false
        models.withGenerator(second) { it.generate(request) }
        assertEquals(listOf("load second", "close second"), runtime.events)
    }
}
