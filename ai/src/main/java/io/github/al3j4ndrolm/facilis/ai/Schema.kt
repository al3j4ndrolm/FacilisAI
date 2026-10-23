package io.github.al3j4ndrolm.facilis.ai

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialInfo
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.SerialKind
import kotlinx.serialization.descriptors.StructureKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Tells the model what a field (or tool argument) means:
 *
 * ```
 * @Serializable data class Reminder(
 *     @Describe("What to remind the user about") val title: String,
 *     @Describe("Minutes from now") val minutes: Int,
 * )
 * ```
 */
@OptIn(ExperimentalSerializationApi::class)
@SerialInfo
@Target(AnnotationTarget.PROPERTY, AnnotationTarget.CLASS)
annotation class Describe(val text: String)

/** Turns Kotlin types into the JSON schemas the model is constrained to, and its JSON back into Kotlin. */
@OptIn(ExperimentalSerializationApi::class)
internal object Schema {

    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** The JSON schema for [descriptor]. */
    fun of(descriptor: SerialDescriptor, depth: Int = 0): JsonObject {
        require(depth < 16) { "${descriptor.serialName} is nested too deeply (recursive types aren't supported)" }
        if (descriptor.isInline) return of(descriptor.getElementDescriptor(0), depth + 1)
        val description = descriptor.annotations.filterIsInstance<Describe>().firstOrNull()?.text
        return when (val kind = descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> type("string", description)
            PrimitiveKind.BOOLEAN -> type("boolean", description)
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> type("integer", description)
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> type("number", description)
            SerialKind.ENUM -> buildJsonObject {
                put("type", "string")
                putJsonArray("enum") { (0 until descriptor.elementsCount).forEach { add(JsonPrimitive(descriptor.getElementName(it))) } }
                description?.let { put("description", it) }
            }
            StructureKind.LIST -> buildJsonObject {
                put("type", "array")
                put("items", of(descriptor.getElementDescriptor(0), depth + 1))
                description?.let { put("description", it) }
            }
            StructureKind.MAP -> buildJsonObject {
                put("type", "object")
                put("additionalProperties", of(descriptor.getElementDescriptor(1), depth + 1))
                description?.let { put("description", it) }
            }
            StructureKind.CLASS, StructureKind.OBJECT -> objectSchema(descriptor, depth, description)
            is PolymorphicKind, SerialKind.CONTEXTUAL ->
                throw IllegalArgumentException("${descriptor.serialName}: sealed, polymorphic and contextual types aren't supported")
            else -> throw IllegalArgumentException("${descriptor.serialName}: unsupported kind $kind")
        }
    }

    private fun objectSchema(descriptor: SerialDescriptor, depth: Int, description: String?) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            for (i in 0 until descriptor.elementsCount) {
                val element = descriptor.getElementDescriptor(i)
                var schema = of(element, depth + 1)
                val hint = descriptor.getElementAnnotations(i).filterIsInstance<Describe>().firstOrNull()?.text
                if (hint != null || element.isNullable) {
                    schema = JsonObject(schema + buildMap {
                        hint?.let { put("description", JsonPrimitive(it)) }
                        if (element.isNullable) put("nullable", JsonPrimitive(true))
                    })
                }
                put(descriptor.getElementName(i), schema)
            }
        }
        val required = (0 until descriptor.elementsCount)
            .filter { !descriptor.isElementOptional(it) && !descriptor.getElementDescriptor(it).isNullable }
        if (required.isNotEmpty()) putJsonArray("required") { required.forEach { add(JsonPrimitive(descriptor.getElementName(it))) } }
        description?.let { put("description", it) }
    }

    private fun type(name: String, description: String?) = buildJsonObject {
        put("type", name)
        description?.let { put("description", it) }
    }

    /** Whether [descriptor] is a JSON object; anything else is wrapped as `{"value": ...}` for the model. */
    fun isObject(descriptor: SerialDescriptor): Boolean =
        !descriptor.isInline && (descriptor.kind == StructureKind.CLASS || descriptor.kind == StructureKind.OBJECT)

    /**
     * How to answer, for the prompt: a JSON example plus what each described field means.
     * Small models copy an example well but tend to echo a JSON schema back.
     */
    fun instructions(descriptor: SerialDescriptor): String {
        val notes = mutableListOf<String>()
        val shape = if (isObject(descriptor)) example(descriptor, notes, 0) else
            "{\"${wrapperKey(descriptor)}\": ${example(descriptor, notes, 0)}}"
        val fields = if (notes.isEmpty()) "" else "\n" + notes.joinToString("\n") { "- $it" }
        return "Answer only with JSON like this:\n$shape$fields"
    }

    private fun example(descriptor: SerialDescriptor, notes: MutableList<String>, depth: Int): String {
        require(depth < 16) { "${descriptor.serialName} is nested too deeply (recursive types aren't supported)" }
        if (descriptor.isInline) return example(descriptor.getElementDescriptor(0), notes, depth + 1)
        val value = when (descriptor.kind) {
            PrimitiveKind.STRING, PrimitiveKind.CHAR -> "\"...\""
            PrimitiveKind.BOOLEAN -> "true"
            PrimitiveKind.BYTE, PrimitiveKind.SHORT, PrimitiveKind.INT, PrimitiveKind.LONG -> "0"
            PrimitiveKind.FLOAT, PrimitiveKind.DOUBLE -> "0.0"
            SerialKind.ENUM -> (0 until descriptor.elementsCount).joinToString(" | ", "\"", "\"") { descriptor.getElementName(it) }
            StructureKind.LIST -> "[${example(descriptor.getElementDescriptor(0), notes, depth + 1)}, ...]"
            StructureKind.MAP -> "{\"...\": ${example(descriptor.getElementDescriptor(1), notes, depth + 1)}}"
            StructureKind.CLASS, StructureKind.OBJECT -> (0 until descriptor.elementsCount).joinToString(", ", "{", "}") { i ->
                val name = descriptor.getElementName(i)
                val element = descriptor.getElementDescriptor(i)
                descriptor.getElementAnnotations(i).filterIsInstance<Describe>().firstOrNull()?.let { notes += "$name: ${it.text}" }
                "\"$name\": ${example(element, notes, depth + 1)}${if (element.isNullable) " or null" else ""}"
            }
            else -> throw IllegalArgumentException("${descriptor.serialName}: sealed, polymorphic and contextual types aren't supported")
        }
        return value
    }

    /** The schema the model answers with: objects as they are, anything else wrapped in `{"items"/"answer": ...}`. */
    fun responseSchema(descriptor: SerialDescriptor): JsonObject =
        if (isObject(descriptor)) of(descriptor) else buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { put(wrapperKey(descriptor), of(descriptor)) }
            putJsonArray("required") { add(JsonPrimitive(wrapperKey(descriptor))) }
        }

    /** Decodes the model's [text] answer for a [responseSchema]. */
    fun <T> decodeResponse(serializer: KSerializer<T>, text: String): T {
        val element = json.parseToJsonElement(extractJson(text))
        val value = if (isObject(serializer.descriptor)) element else
            (element as? JsonObject)?.let { it[wrapperKey(serializer.descriptor)] ?: it.values.singleOrNull() } ?: element
        return json.decodeFromJsonElement(serializer, value)
    }

    /** The JSON part of [text], dropping code fences or chatter some models add around it. */
    fun extractJson(text: String): String {
        val cleaned = Thinking.strip(text).trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOfFirst { it == '{' || it == '[' }
        val end = cleaned.indexOfLast { it == '}' || it == ']' }
        return if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned
    }

    /** Converts LiteRT-LM's tool arguments (Gson-parsed) to JSON, so whole numbers decode into Int fields. */
    fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is JsonElement -> value
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> {
            val d = value.toDouble()
            if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 9.0e15) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
        }
        is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
        is Iterable<*> -> JsonArray(value.map { toJson(it) })
        is Array<*> -> JsonArray(value.map { toJson(it) })
        else -> JsonPrimitive(value.toString())
    }

    /** Converts JSON to the plain Kotlin values LiteRT-LM accepts as a tool result. */
    fun toPlain(element: JsonElement): Any? = when (element) {
        is JsonNull -> null
        is JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" || element.content == "false" -> element.content.toBoolean()
            else -> element.content.toLongOrNull() ?: element.content.toDoubleOrNull() ?: element.content
        }
        is JsonObject -> element.mapValues { toPlain(it.value) }
        is JsonArray -> element.map { toPlain(it) }
    }

    // A meaningful key helps small models: with "value" they tend to give a one-item list.
    private fun wrapperKey(descriptor: SerialDescriptor) = if (descriptor.kind == StructureKind.LIST) "items" else "answer"
}

/** Removes the `<think>…</think>` reasoning some models (like Qwen3) write before answering. */
internal object Thinking {

    private const val OPEN = "<think>"
    private const val CLOSE = "</think>"

    fun strip(text: String): String {
        var result = text
        while (true) {
            val start = result.indexOf(OPEN)
            if (start < 0) break
            val end = result.indexOf(CLOSE, start)
            result = if (end < 0) result.substring(0, start) else result.substring(0, start) + result.substring(end + CLOSE.length)
        }
        return result.replace(CLOSE, "")
    }

    /** Filters streamed chunks, holding back anything that might be the start of a tag. */
    class Filter {
        private var inside = false
        private var pending = ""
        private var started = false

        fun push(chunk: String): String {
            pending += chunk
            val out = StringBuilder()
            while (pending.isNotEmpty()) {
                if (inside) {
                    val end = pending.indexOf(CLOSE)
                    if (end < 0) {
                        pending = pending.takeLast(CLOSE.length - 1)
                        break
                    }
                    pending = pending.substring(end + CLOSE.length)
                    inside = false
                } else {
                    val start = pending.indexOf(OPEN)
                    if (start >= 0) {
                        out.append(pending, 0, start)
                        pending = pending.substring(start + OPEN.length)
                        inside = true
                    } else {
                        val keep = partialTagLength(pending)
                        out.append(pending, 0, pending.length - keep)
                        pending = pending.substring(pending.length - keep)
                        break
                    }
                }
            }
            return trimStart(out.toString())
        }

        fun finish(): String {
            val rest = if (inside) "" else pending
            pending = ""
            return trimStart(rest)
        }

        // The answer usually starts with blank lines after the reasoning; drop them.
        private fun trimStart(text: String): String {
            if (started) return text
            val trimmed = text.trimStart()
            if (trimmed.isNotEmpty()) started = true
            return trimmed
        }

        private fun partialTagLength(text: String): Int {
            for (length in minOf(OPEN.length - 1, text.length) downTo 1) {
                if (OPEN.startsWith(text.takeLast(length))) return length
            }
            return 0
        }
    }
}
