package io.github.valerioisufi.pageboard.lecture

import kotlinx.serialization.json.Json

/**
 * The lectures as the devices working on a notebook have them: changed apart (a lecture recorded
 * on the tablet, transcribed and reviewed on the computer, its notes inserted on both), merged
 * when they meet ([merge]).
 *
 * Every part of a lecture ([LecturePart]) keeps its most recent change; the suggestions are
 * gathered from both, each with its most recent status. The merge gives the same lecture on both
 * devices, whatever the order: its [version] tells them apart.
 */
internal object LectureRecords {

    /** [mine] merged with [theirs] (the same lecture on another device): the suggestions in the order of [mine]. */
    fun merge(mine: LectureRecord, theirs: LectureRecord): LectureRecord {
        var merged = mine.copy(
            recordedBy = mine.recordedBy.ifEmpty { theirs.recordedBy },
            recordedOn = mine.recordedOn.ifEmpty { theirs.recordedOn }
        )
        val stamps = mutableMapOf<LecturePart, Stamp>()
        for (part in LecturePart.entries) {
            val my = mine.stampOf(part)
            val their = theirs.stampOf(part)
            if (their > my || (their == my && newerOnTie(part.of(theirs), part.of(mine)))) merged = part.copy(merged, theirs)
            maxOf(my, their).takeIf { it != Stamp.NONE }?.let { stamps[part] = it }
        }
        // Each device counts its own tokens: the most recent count of each one
        val devices = mine.usageByDevice.keys + theirs.usageByDevice.keys
        merged = merged.copy(usageByDevice = devices.associateWith { device ->
            val my = mine.usageByDevice[device]
            val their = theirs.usageByDevice[device]
            if (my == null || (their != null && their.stamp > my.stamp)) their!! else my
        })
        val theirSuggestions = theirs.suggestions.associateBy { it.id }
        val myIds = mine.suggestions.mapTo(mutableSetOf()) { it.id }
        val suggestions = mine.suggestions.map { suggestion -> theirSuggestions[suggestion.id]?.let { newer(suggestion, it) } ?: suggestion } +
            theirs.suggestions.filter { it.id !in myIds }
        return merged.copy(suggestions = suggestions, stamps = stamps)
    }

    /**
     * [record] with the stamps of the parts changed since [before] (the lecture as last saved or
     * merged) on [device] at [now].
     */
    fun stamped(record: LectureRecord, before: LectureRecord?, device: String, now: Long): LectureRecord {
        val base = before ?: LectureRecord(id = record.id, documentId = record.documentId, startedAt = record.startedAt)
        val stamps = record.stamps.toMutableMap()
        for (part in LecturePart.entries) {
            if (part.of(record) != part.of(base)) stamps[part] = record.stampOf(part).next(device, now)
        }
        // The tokens of this device
        val usage = record.usageByDevice[device]?.let { mine ->
            val previous = base.usageByDevice[device]
            if (previous != null && previous.usage == mine.usage && previous.name == mine.name) previous else mine.copy(stamp = (previous?.stamp ?: mine.stamp).next(device, now))
        }
        return record.copy(stamps = stamps, usageByDevice = if (usage == null) record.usageByDevice else record.usageByDevice + (device to usage))
    }

    /** What tells [record] apart: the same on two devices with the same lecture (the order of the suggestions aside). */
    fun version(record: LectureRecord): String = "r" + contentHash(canonical(record).encodeToByteArray())

    /** [record] as sent to the other devices. */
    fun encode(record: LectureRecord): ByteArray = json.encodeToString(LectureRecord.serializer(), record).encodeToByteArray()

    /** The same text for the same lecture. */
    private fun canonical(record: LectureRecord): String {
        val canonical = record.copy(
            suggestions = record.suggestions.sortedBy { it.id },
            usageByDevice = record.usageByDevice.entries.sortedBy { it.key }.associate { (device, usage) ->
                device to usage.copy(usage = usage.usage.copy(promptByModality = usage.usage.promptByModality.entries.sortedBy { it.key }.associate { it.toPair() }))
            },
            stamps = record.stamps.entries.sortedBy { it.key }.associate { it.toPair() }
        )
        return json.encodeToString(LectureRecord.serializer(), canonical)
    }

    fun decode(bytes: ByteArray): LectureRecord? = try {
        json.decodeFromString(LectureRecord.serializer(), bytes.decodeToString())
    } catch (_: Exception) {
        null
    }

    /** Of the same suggestion on two devices, the one with the most recent status. */
    private fun newer(mine: NoteSuggestion, theirs: NoteSuggestion): NoteSuggestion {
        val my = mine.statusStamp ?: Stamp.NONE
        val their = theirs.statusStamp ?: Stamp.NONE
        return if (their > my || (their == my && newerOnTie(theirs, mine))) theirs else mine
    }

    /** Two contents changed at the same moment (e.g. never changed, on two devices): the same one is kept on both. */
    private fun newerOnTie(theirs: Any?, mine: Any?): Boolean = theirs != mine && theirs.toString() > mine.toString()

    private fun LecturePart.of(record: LectureRecord): Any? = when (this) {
        LecturePart.RECORDING -> listOf(record.documentTitle, record.durationMs, record.status, record.pages)
        LecturePart.OUTLINE -> record.outline to record.currentTopic
        LecturePart.TRANSCRIPT -> record.transcript
        LecturePart.REVIEW -> Triple(record.finalReview, record.finalReviewBy, record.finalReviewProvider)
        LecturePart.SHARING -> record.audioShared
        LecturePart.GENERATION -> record.generation
    }

    /** [target] with this part of [from]. */
    private fun LecturePart.copy(target: LectureRecord, from: LectureRecord): LectureRecord = when (this) {
        LecturePart.RECORDING -> target.copy(documentTitle = from.documentTitle, durationMs = from.durationMs, status = from.status, pages = from.pages)
        LecturePart.OUTLINE -> target.copy(outline = from.outline, currentTopic = from.currentTopic)
        LecturePart.TRANSCRIPT -> target.copy(transcript = from.transcript)
        LecturePart.REVIEW -> target.copy(finalReview = from.finalReview, finalReviewBy = from.finalReviewBy, finalReviewProvider = from.finalReviewProvider)
        LecturePart.SHARING -> target.copy(audioShared = from.audioShared)
        LecturePart.GENERATION -> target.copy(generation = from.generation)
    }

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
}

/** A hash of [bytes] (FNV-1a, 64 bits) telling contents apart, in hexadecimal. */
internal fun contentHash(bytes: ByteArray): String {
    var hash = FNV_OFFSET
    for (byte in bytes) {
        hash = hash xor (byte.toLong() and 0xFF)
        hash *= FNV_PRIME
    }
    return hash.toULong().toString(16).padStart(16, '0')
}

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
