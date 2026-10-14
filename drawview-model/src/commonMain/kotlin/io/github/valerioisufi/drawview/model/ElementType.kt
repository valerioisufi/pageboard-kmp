package io.github.valerioisufi.drawview.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.cbor.Cbor

/**
 * A kind of element added by an application or a library (a table, a shape, a recording...): the
 * value of its elements ([T], immutable and serializable), stored in the documents as CBOR (see
 * [DrawCustomElement]).
 *
 * The documents carry the elements of the types they do not know as they are: every device keeps
 * them, synchronizes them and draws a placeholder in their box, so that the elements of a device
 * with the type are never lost.
 *
 * A type is drawn by an `ElementRenderer` of `drawview-canvas`; it is placed, selected, moved,
 * rotated and resized by the editor through its box.
 */
interface ElementType<T : Any> {
    /**
     * The identifier of the type, the same on every device and in every version of the
     * application: a reverse domain name (`com.example.table`); `drawview.*` is reserved.
     */
    val id: String

    /** The format of the value; a value written in an older format is read by [migrate]. */
    val version: Int get() = 1

    /** Writes and reads the value in the current [version]. */
    val serializer: KSerializer<T>

    /**
     * The value written by an older version of the type (format [fromVersion]), or `null` when
     * it cannot be read (the element is then drawn as a placeholder).
     */
    fun migrate(payload: ByteArray, fromVersion: Int): T? = null
}

/** [value] as the payload of an element of this type (see [DrawCustomElement.payload]). */
@OptIn(ExperimentalSerializationApi::class)
fun <T : Any> ElementType<T>.encode(value: T): ByteArray = Cbor.encodeToByteArray(serializer, value)

/**
 * The value of [element] when it is of this type and readable (its format is not newer than
 * [ElementType.version]), `null` otherwise.
 */
@OptIn(ExperimentalSerializationApi::class)
fun <T : Any> ElementType<T>.decode(element: DrawCustomElement): T? {
    if (element.type != id) return null
    val payload = element.payload
    val version = element.version
    return when {
        version == this.version -> try {
            Cbor.decodeFromByteArray(serializer, payload)
        } catch (_: Exception) {
            null
        }
        version < this.version -> migrate(payload, version)
        else -> null
    }
}
