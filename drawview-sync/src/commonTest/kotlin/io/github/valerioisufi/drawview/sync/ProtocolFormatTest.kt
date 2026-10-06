package io.github.valerioisufi.drawview.sync

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.descriptors.elementDescriptors
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The messages of the protocol have the format recorded for its version ([PROTOCOL_FORMATS]): a
 * change of the format (a new kind of change, a field added, renamed or of another type...) does
 * not go unnoticed. The devices of another format cannot read what the others send (see
 * [SyncEngine.otherVersionMet]): once the applications are released, [PROTOCOL_VERSION] is raised
 * with the format (before, the format is recorded again under the same version).
 */
class ProtocolFormatTest {

    @Test
    fun theMessagesHaveTheFormatRecordedForTheVersionOfTheProtocol() {
        val format = describe(OpenFrame.serializer().descriptor, SyncMessage.serializer().descriptor)
        assertEquals(
            PROTOCOL_FORMATS[PROTOCOL_VERSION],
            format,
            "The format of the messages changed: record it in PROTOCOL_FORMATS (once the applications are " +
                "released, raise PROTOCOL_VERSION first). The format now:\n$format\n"
        )
    }

    /**
     * Every kind of value the messages from [roots] are made of, one line each, in their order
     * (kinds of different hierarchies may have the same name: e.g. the stroke and its placement).
     */
    @OptIn(ExperimentalSerializationApi::class)
    private fun describe(vararg roots: SerialDescriptor): String {
        val visited = mutableListOf<SerialDescriptor>()
        val lines = mutableSetOf<String>()

        fun typeOf(descriptor: SerialDescriptor): String = when (descriptor.kind) {
            StructureKind.LIST -> "List<${typeOf(descriptor.getElementDescriptor(0))}>"
            StructureKind.MAP -> "Map<${typeOf(descriptor.getElementDescriptor(0))}, ${typeOf(descriptor.getElementDescriptor(1))}>"
            else -> descriptor.serialName
        } + if (descriptor.isNullable && !descriptor.serialName.endsWith("?")) "?" else ""

        fun visit(descriptor: SerialDescriptor) {
            if (visited.any { it === descriptor }) return
            visited.add(descriptor)
            // The collections are described by their elements
            if (descriptor.kind == StructureKind.LIST || descriptor.kind == StructureKind.MAP) {
                descriptor.elementDescriptors.forEach(::visit)
                return
            }
            val fields = (0 until descriptor.elementsCount).joinToString { i ->
                val field = descriptor.getElementName(i) + if (descriptor.isElementOptional(i)) "?" else ""
                // The entries of an enumeration are their names
                if (descriptor.kind == SerialKind.ENUM) field else "$field: ${typeOf(descriptor.getElementDescriptor(i))}"
            }
            lines.add("${descriptor.serialName.removeSuffix("?")} ${descriptor.kind}($fields)")
            if (descriptor.kind != SerialKind.ENUM) descriptor.elementDescriptors.forEach(::visit)
        }

        roots.forEach(::visit)
        return lines.sorted().joinToString("\n")
    }
}
