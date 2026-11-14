package io.github.valerioisufi.drawview.sync.protocol

/** What an [Invitation] is to: a document, or a shared folder (a folder of the library, or the library itself, see `docs/shared-folders.md`). */
public enum class InvitationKind { DOCUMENT, FOLDER, LIBRARY }

/**
 * What a device needs to join a shared document: its identifier, its title, the secret of the
 * invitation ([key]: not the key of the document, which the owner gives to the devices it
 * admits), the owner ([owner]: a fingerprint of its identity, see [DeviceIdentity.fingerprint],
 * so that the device joining knows it talks to it) and where it can be reached ([addresses]: by
 * transport, in the terms of each [SyncTransport]; none: to be found on the network).
 *
 * [kind]: of a shared folder, [documentId] is the identifier of the space and [title] its name.
 */
public data class Invitation(
    val documentId: String,
    val title: String,
    val key: DocumentKey,
    val owner: String,
    val addresses: Map<String, String> = emptyMap(),
    val kind: InvitationKind = InvitationKind.DOCUMENT
) {
    /** The invitation as a link (for a QR code or a message), read back by [parse]. */
    public fun toLink(): String = buildString {
        when (kind) {
            InvitationKind.DOCUMENT -> append(SCHEME).append("?d=").append(encode(documentId))
            InvitationKind.FOLDER -> append(SCHEME).append("?s=").append(encode(documentId)).append("&n=folder")
            InvitationKind.LIBRARY -> append(SCHEME).append("?s=").append(encode(documentId)).append("&n=library")
        }
        append("&k=").append(key.encode())
        append("&o=").append(encode(owner))
        append("&t=").append(encode(title))
        for ((transport, address) in addresses.entries.sortedBy { it.key }) {
            append("&a.").append(encode(transport)).append('=').append(encode(address))
        }
    }

    public companion object {
        private const val SCHEME = "drawview://join"

        /**
         * The transport of an address written `a=` (the links of before: the address on the local
         * network, see `LanTransport`); the links are kept, by the devices joining.
         */
        private const val LEGACY_TRANSPORT = "lan"

        /** The invitation of a link written by [toLink], or `null` when [link] is not one. */
        public fun parse(link: String): Invitation? {
            val query = link.trim().removePrefix(SCHEME).takeIf { it.startsWith("?") }?.drop(1) ?: return null
            val fields = query.split('&').mapNotNull { field ->
                val separator = field.indexOf('=')
                if (separator <= 0) null else field.take(separator) to decode(field.substring(separator + 1))
            }.toMap()
            val addresses = fields.mapNotNull { (name, value) ->
                when {
                    name == "a" -> LEGACY_TRANSPORT to value
                    name.startsWith("a.") && name.length > 2 && value.isNotEmpty() -> name.substring(2) to value
                    else -> null
                }
            }.toMap()
            val space = fields["s"]?.takeIf { it.isNotEmpty() }
            val kind = when {
                space == null -> InvitationKind.DOCUMENT
                fields["n"] == "library" -> InvitationKind.LIBRARY
                else -> InvitationKind.FOLDER
            }
            return Invitation(
                documentId = space ?: fields["d"]?.takeIf { it.isNotEmpty() } ?: return null,
                title = fields["t"].orEmpty(),
                key = fields["k"]?.let { DocumentKey.decode(it) } ?: return null,
                owner = fields["o"]?.takeIf { it.isNotEmpty() } ?: return null,
                addresses = addresses,
                kind = kind
            )
        }

        private const val UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

        private fun encode(text: String): String = buildString {
            for (byte in text.encodeToByteArray()) {
                val char = (byte.toInt() and 0xFF).toChar()
                if (char in UNRESERVED) append(char) else append('%').append((byte.toInt() and 0xFF).toString(16).uppercase().padStart(2, '0'))
            }
        }

        private fun decode(text: String): String {
            val bytes = ArrayList<Byte>(text.length)
            var i = 0
            while (i < text.length) {
                val char = text[i]
                if (char == '%' && i + 2 < text.length) {
                    val value = text.substring(i + 1, i + 3).toIntOrNull(16)
                    if (value != null) {
                        bytes.add(value.toByte())
                        i += 3
                        continue
                    }
                }
                char.toString().encodeToByteArray().forEach { bytes.add(it) }
                i++
            }
            return bytes.toByteArray().decodeToString()
        }
    }
}
