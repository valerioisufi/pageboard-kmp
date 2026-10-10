package io.github.valerioisufi.drawview.model

import io.github.valerioisufi.drawview.model.change.RemovePage
import io.github.valerioisufi.drawview.model.change.UpdateTexts
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * The changes of a piece of a document that a version of it includes: for every replica, the
 * stamp of its last one (the changes of a replica follow each other, see [Stamp]).
 *
 * They tell whether two versions were made apart (neither includes the other): two devices that
 * changed the same text without seeing each other's change (see `UpdateTexts`), a removal of a
 * page that did not see what was added to it (see `RemovePage`).
 *
 * Written as the stamps, in their order, separated by commas.
 */
@Serializable(with = VersionsSerializer::class)
data class Versions(val stamps: Map<String, Stamp> = emptyMap()) {

    /** These and the change of [stamp]. */
    operator fun plus(stamp: Stamp): Versions =
        if ((stamps[stamp.replica] ?: Stamp.ZERO) >= stamp) this else Versions(stamps + (stamp.replica to stamp))

    /** Whether these include every change of [other]. */
    fun covers(other: Versions): Boolean = other.stamps.all { (replica, stamp) -> (stamps[replica] ?: Stamp.ZERO) >= stamp }

    override fun toString(): String = stamps.values.sorted().joinToString(",")

    companion object {
        /** No change. */
        val NONE = Versions()

        fun parse(text: String): Versions =
            Versions(text.split(',').mapNotNull { Stamp.parse(it) }.associateBy { it.replica })
    }
}

internal object VersionsSerializer : KSerializer<Versions> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Versions", PrimitiveKind.STRING)
    override fun serialize(encoder: Encoder, value: Versions) = encoder.encodeString(value.toString())
    override fun deserialize(decoder: Decoder): Versions = Versions.parse(decoder.decodeString())
}
