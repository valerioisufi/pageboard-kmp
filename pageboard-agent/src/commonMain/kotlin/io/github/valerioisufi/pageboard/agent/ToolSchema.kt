package io.github.valerioisufi.pageboard.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

internal fun JsonObject.string(name: String): String? = (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

internal fun JsonObject.int(name: String): Int? = (get(name) as? JsonPrimitive)?.intOrNull

internal fun JsonObject.boolean(name: String): Boolean? = (get(name) as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

internal fun JsonObject.requiredString(name: String): String =
    string(name)?.takeIf { it.isNotBlank() }
        ?: throw AgentToolException(AgentErrorCode.BAD_REQUEST, "The argument \"$name\" is needed, as a string.")

/** The schema of the arguments of a tool: an object of the [properties] written by [build]. */
internal fun schema(required: List<String> = emptyList(), build: SchemaBuilder.() -> Unit = {}): JsonObject = buildJsonObject {
    put("type", "object")
    val builder = SchemaBuilder().apply(build)
    put("properties", JsonObject(builder.properties))
    putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } }
    put("additionalProperties", false)
}

internal class SchemaBuilder {
    val properties = linkedMapOf<String, JsonElement>()

    fun string(name: String, description: String) = property(name, "string", description)

    fun integer(name: String, description: String) = property(name, "integer", description)

    fun boolean(name: String, description: String) = property(name, "boolean", description)

    private fun property(name: String, type: String, description: String) {
        properties[name] = buildJsonObject {
            put("type", type)
            put("description", description)
        }
    }

    fun putJsonObject(name: String, build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) {
        properties[name] = buildJsonObject(build)
    }
}
