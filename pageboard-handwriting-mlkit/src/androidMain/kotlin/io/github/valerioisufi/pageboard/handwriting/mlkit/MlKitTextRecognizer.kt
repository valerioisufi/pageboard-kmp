package io.github.valerioisufi.pageboard.handwriting.mlkit

import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import com.google.mlkit.vision.digitalink.recognition.RecognitionContext
import com.google.mlkit.vision.digitalink.recognition.WritingArea
import io.github.valerioisufi.pageboard.handwriting.HandwritingEngines
import io.github.valerioisufi.pageboard.handwriting.InkLanguageModel
import io.github.valerioisufi.pageboard.handwriting.InkLanguageModels
import io.github.valerioisufi.pageboard.handwriting.InkReading
import io.github.valerioisufi.pageboard.handwriting.InkSample
import io.github.valerioisufi.pageboard.handwriting.TextInkRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit Digital Ink Recognition as a reader of text: a line of samples at a time, with the text
 * written before it ([RecognitionContext.preContext], its last [PRE_CONTEXT_CHARS] characters) and
 * the size of the line as the writing area. Reads the languages whose model is on the device
 * ([MlKitLanguageModels]); the candidates of ML Kit are the alternatives of the reading.
 */
public class MlKitTextRecognizer(
    private val models: MlKitLanguageModels = MlKitLanguageModels()
) : TextInkRecognizer {
    private val lock = Mutex()
    private val recognizers = mutableMapOf<String, DigitalInkRecognizer>()

    override val version: String = "ML Kit Digital Ink $LIBRARY_VERSION"

    override suspend fun languages(): Set<String> = models.downloaded()

    override suspend fun recognize(ink: InkSample, language: String, context: String): InkReading? {
        if (ink.isEmpty) return null
        val tag = languages().firstOrNull { HandwritingEngines.sameLanguage(it, language) } ?: return null
        val recognizer = lock.withLock {
            recognizers.getOrPut(tag) {
                val model = models.modelOf(tag) ?: return null
                DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(model).build())
            }
        }
        val bounds = ink.bounds ?: return null
        val builder = Ink.builder()
        for (stroke in ink.strokes) {
            val strokeBuilder = Ink.Stroke.builder()
            for (p in stroke) strokeBuilder.addPoint(Ink.Point.create((p.x - bounds.left) * UNITS_PER_MM, (p.y - bounds.top) * UNITS_PER_MM, p.timestampMs))
            builder.addStroke(strokeBuilder.build())
        }
        val recognitionContext = RecognitionContext.builder()
            .setPreContext(context.takeLast(PRE_CONTEXT_CHARS))
            .setWritingArea(WritingArea((bounds.right - bounds.left) * UNITS_PER_MM, (bounds.bottom - bounds.top) * UNITS_PER_MM))
            .build()
        val result = recognizer.recognize(builder.build(), recognitionContext).await()
        val candidates = result.candidates.map { it.text }.filter { it.isNotBlank() }
        if (candidates.isEmpty()) return null
        return InkReading(candidates.first(), candidates.drop(1), candidates.distinct().joinToString(" "))
    }

    /** Frees the recognizers of ML Kit; the reader opens them again when it reads. */
    public suspend fun close() {
        lock.withLock {
            recognizers.values.forEach { it.close() }
            recognizers.clear()
        }
    }

    private companion object {
        const val LIBRARY_VERSION = "19.0.0"
        const val PRE_CONTEXT_CHARS = 20

        /** The samples given to ML Kit in tenths of a millimeter (it takes any unit, the writing area in the same). */
        const val UNITS_PER_MM = 10f
    }
}

/**
 * The models of the languages of ML Kit Digital Ink Recognition, downloaded from Google only when
 * the user asks ([download]), kept by ML Kit and deleted by [delete]. Without Google Play services
 * nothing downloads: the list stays empty.
 */
public class MlKitLanguageModels : InkLanguageModels {
    private val manager by lazy { RemoteModelManager.getInstance() }

    /** The languages whose model is on the device, as BCP 47 tags. */
    public suspend fun downloaded(): Set<String> = try {
        manager.getDownloadedModels(DigitalInkRecognitionModel::class.java).await()
            .mapTo(mutableSetOf()) { it.modelIdentifier.languageTag }
    } catch (_: Exception) {
        emptySet()
    }

    override suspend fun models(languages: Collection<String>): List<InkLanguageModel> {
        val present = downloaded()
        return languages.mapNotNull { language ->
            val identifier = identifierOf(language) ?: return@mapNotNull null
            InkLanguageModel(identifier.languageTag, identifier.languageTag in present, MODEL_BYTES)
        }
    }

    override suspend fun download(language: String, wifiOnly: Boolean) {
        val model = modelOf(language) ?: throw IllegalArgumentException("ML Kit reads no handwriting in $language")
        val conditions = DownloadConditions.Builder().apply { if (wifiOnly) requireWifi() }.build()
        manager.download(model, conditions).await()
    }

    override suspend fun delete(language: String) {
        val model = modelOf(language) ?: return
        manager.deleteDownloadedModel(model).await()
    }

    internal fun modelOf(language: String): DigitalInkRecognitionModel? =
        identifierOf(language)?.let { DigitalInkRecognitionModel.builder(it).build() }

    private fun identifierOf(language: String): DigitalInkRecognitionModelIdentifier? =
        try {
            DigitalInkRecognitionModelIdentifier.fromLanguageTag(language)
                ?: DigitalInkRecognitionModelIdentifier.fromLanguageTag(language.substringBefore('-'))
        } catch (_: Exception) {
            null
        }

    private companion object {
        /** About the size of a model of a language (ML Kit does not say it before the download). */
        const val MODEL_BYTES = 20L * 1024 * 1024
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { continuation.resume(it) }
    addOnFailureListener { continuation.resumeWithException(it) }
    addOnCanceledListener { continuation.cancel() }
}
