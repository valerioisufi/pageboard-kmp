package io.github.valerioisufi.pageboard.lecture

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull

class LectureRecordsTest {
    private val recorded = LectureRecord(
        id = "2026-10-06_10-00-00-a1b2c3",
        documentId = "doc",
        documentTitle = "Analisi 1",
        startedAt = 1_000,
        durationMs = 60_000,
        recordedBy = "tablet",
        recordedOn = "iPad",
        suggestions = listOf(note("n1"), note("n2"))
    ).let { LectureRecords.stamped(it, null, "tablet", 100) }

    private fun note(id: String) = NoteSuggestion(id = id, kind = NoteKind.DEFINITION, title = id, content = "Content of $id")

    private fun LectureRecord.withStatus(id: String, status: SuggestionStatus, device: String, at: Long) = copy(
        suggestions = suggestions.map { if (it.id == id) it.copy(status = status, statusStamp = (it.statusStamp ?: Stamp.NONE).next(device, at)) else it }
    )

    @Test
    fun aSuggestionWrittenBeforeTheAnchorsIsWrittenAgainAsItWas() {
        // A suggestion as the versions before the anchors wrote it (every value, defaults too)
        val before = """{"id":"n1","kind":"DEFINITION","title":"n1","content":"Content of n1","pageIndex":3,"pageId":"p3","importance":2,"atMs":12000,"fromFinalReview":false,"status":"NEW","statusStamp":null}"""
        val json = kotlinx.serialization.json.Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val read = json.decodeFromString(NoteSuggestion.serializer(), before)
        assertEquals("", read.anchor)
        assertEquals(before, json.encodeToString(NoteSuggestion.serializer(), read))
        // So the version of a record without anchors does not change
        val record = recorded.copy(suggestions = listOf(read))
        assertEquals(LectureRecords.version(record), LectureRecords.version(assertNotNull(LectureRecords.decode(LectureRecords.encode(record)))))
    }

    @Test
    fun theAnchorOfASuggestionIsKeptAndMerged() {
        val anchored = recorded.copy(suggestions = recorded.suggestions.map { it.copy(anchor = "limite di una funzione") })
        val read = assertNotNull(LectureRecords.decode(LectureRecords.encode(anchored)))
        assertEquals(listOf("limite di una funzione", "limite di una funzione"), read.suggestions.map { it.anchor })
        // An older version reads it, ignoring the anchor (as it reads any key it does not know)
        val old = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        val suggestion = old.encodeToString(NoteSuggestion.serializer(), anchored.suggestions.first())
        assertEquals("n1", old.decodeFromString(OldSuggestion.serializer(), suggestion).id)
        val merged = LectureRecords.merge(recorded.withStatus("n1", SuggestionStatus.INSERTED, "tablet", 300), anchored)
        assertEquals(SuggestionStatus.INSERTED, merged.suggestions.first().status)
        assertNotEquals(LectureRecords.version(recorded), LectureRecords.version(anchored))
    }

    @Test
    fun eachPartKeepsItsMostRecentChange() {
        // The computer transcribes and reviews; meanwhile the tablet renames the lecture
        val computer = LectureRecords.stamped(
            recorded.copy(transcript = listOf(TranscriptSegment(0, 60_000, "Il limite")), finalReview = "# Limiti", finalReviewBy = "gemini"),
            recorded, "computer", 200
        )
        val tablet = LectureRecords.stamped(recorded.copy(documentTitle = "Analisi I"), recorded, "tablet", 300)

        val merged = LectureRecords.merge(tablet, computer)
        assertEquals("Analisi I", merged.documentTitle)
        assertEquals("# Limiti", merged.finalReview)
        assertEquals("gemini", merged.finalReviewBy)
        assertEquals(listOf(TranscriptSegment(0, 60_000, "Il limite")), merged.transcript)
        assertEquals(Stamp(200, "computer"), merged.stampOf(LecturePart.REVIEW))
        assertEquals(Stamp(300, "tablet"), merged.stampOf(LecturePart.RECORDING))
        // The same lecture on both, whatever the order
        assertEquals(LectureRecords.version(merged), LectureRecords.version(LectureRecords.merge(computer, tablet)))
        assertEquals(merged, LectureRecords.merge(merged, computer))
    }

    @Test
    fun theSuggestionsAreGatheredWithTheirMostRecentStatus() {
        val computer = recorded
            .copy(suggestions = recorded.suggestions + note("n3"))
            .withStatus("n1", SuggestionStatus.DISMISSED, "computer", 200)
            .withStatus("n2", SuggestionStatus.DISMISSED, "computer", 200)
        // Inserted later on the tablet
        val tablet = recorded.withStatus("n2", SuggestionStatus.INSERTED, "tablet", 300)

        val merged = LectureRecords.merge(tablet, computer)
        assertEquals(listOf("n1", "n2", "n3"), merged.suggestions.map { it.id })
        assertEquals(listOf(SuggestionStatus.DISMISSED, SuggestionStatus.INSERTED, SuggestionStatus.NEW), merged.suggestions.map { it.status })
        val other = LectureRecords.merge(computer, tablet)
        assertEquals(listOf("n1", "n2", "n3"), other.suggestions.map { it.id })
        assertEquals(LectureRecords.version(merged), LectureRecords.version(other))
    }

    @Test
    fun onlyThePartsChangedAreStamped() {
        val changed = LectureRecords.stamped(recorded.copy(documentTitle = "Analisi I", outline = "- Limiti"), recorded, "computer", 50)
        assertEquals(Stamp(50, "computer"), changed.stampOf(LecturePart.OUTLINE))
        // After the change it follows, even with a clock behind
        assertEquals(Stamp(101, "computer"), changed.stampOf(LecturePart.RECORDING))
        assertEquals(Stamp.NONE, changed.stampOf(LecturePart.REVIEW))
        assertNotEquals(LectureRecords.version(recorded), LectureRecords.version(changed))
    }

    @Test
    fun twoContentsNeverChangedAreMergedTheSameWay() {
        val mine = recorded.copy(stamps = emptyMap(), outline = "a")
        val theirs = recorded.copy(stamps = emptyMap(), outline = "b")
        assertEquals(LectureRecords.merge(mine, theirs).outline, LectureRecords.merge(theirs, mine).outline)
    }

    @Test
    fun aLectureTranscribedByGeminiIsReadAsItWas() {
        // As saved on 2026-10-15: transcribed by Gemini from its audio, the audio counted in its tokens
        val saved = """{"id":"2026-10-15_09-48-14-2ce0b6","documentId":"a41ffe77","documentTitle":"SERT","startedAt":1792054094000,"durationMs":4366000,
            "status":"FINISHED","outline":"- Validazione","currentTopic":"EDF",
            "transcript":[{"startMs":0,"endMs":60000,"text":"Buongiorno, oggi parliamo di validazione."},{"startMs":60000,"endMs":120000,"text":"Il fattore di utilizzazione."}],
            "usageByDevice":{"0236e586":{"name":"Valerio pc","usage":{"requests":49,"promptTokens":326830,"outputTokens":55316,"promptByModality":{"TEXT":274990,"AUDIO":51840}}}},
            "finalReviewProvider":"GEMINI"}"""
        val record = assertNotNull(LectureRecords.decode(saved.encodeToByteArray()))
        assertEquals(listOf("Buongiorno, oggi parliamo di validazione.", "Il fattore di utilizzazione."), record.transcript.map { it.text })
        assertEquals(mapOf("TEXT" to 274_990L, "AUDIO" to 51_840L), record.usageByDevice.getValue("0236e586").usage.promptByModality)
        assertEquals(LectureStatus.FINISHED, record.status)
        assertEquals("", record.topic)
        val reencoded = LectureRecords.encode(record).decodeToString()
        assertEquals(false, "\"topic\"" in reencoded)
        assertEquals(LectureRecords.version(record), LectureRecords.version(assertNotNull(LectureRecords.decode(reencoded.encodeToByteArray()))))
    }

    @Test
    fun aGeneratedTopicIsSavedMergedAndIgnoredByOlderReaders() {
        val withTopic = LectureRecords.stamped(
            recorded.copy(topic = "Limiti e continuità"),
            recorded,
            "computer",
            250
        )
        val encoded = LectureRecords.encode(withTopic)
        val decoded = assertNotNull(LectureRecords.decode(encoded))
        assertEquals("Limiti e continuità", decoded.topic)

        // Merged under LecturePart.OUTLINE regardless of device order
        val mergedA = LectureRecords.merge(recorded, decoded)
        val mergedB = LectureRecords.merge(decoded, recorded)
        assertEquals("Limiti e continuità", mergedA.topic)
        assertEquals("Limiti e continuità", mergedB.topic)
        assertEquals(LectureRecords.version(mergedA), LectureRecords.version(mergedB))
        assertNotEquals(LectureRecords.version(recorded), LectureRecords.version(decoded))

        // Older deserializers with ignoreUnknownKeys = true read it without failing
        val older = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        assertEquals("2026-10-06_10-00-00-a1b2c3", older.decodeFromString(LegacyRecord.serializer(), encoded.decodeToString()).id)
    }

    @kotlinx.serialization.Serializable
    private data class LegacyRecord(val id: String, val documentId: String, val startedAt: Long)

    /** A suggestion as the versions before the anchors know it. */
    @kotlinx.serialization.Serializable
    private data class OldSuggestion(val id: String, val kind: NoteKind, val title: String, val content: String)
}
