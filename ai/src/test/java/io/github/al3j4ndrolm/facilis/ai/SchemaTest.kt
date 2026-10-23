package io.github.al3j4ndrolm.facilis.ai

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SchemaTest {

    @Serializable
    data class Event(
        val title: String,
        @Describe("Day and time") val time: String,
        val place: String?,
        val bring: List<String>,
        val attendees: Int = 0,
    )

    @Serializable
    enum class Mood { Positive, Neutral, Negative }

    @Serializable
    data class Receipt(val store: String, val total: Double, val items: List<Item>)

    @Serializable
    data class Item(val name: String, val price: Double)

    @Serializable
    data class Reminder(val title: String, val minutes: Int)

    private fun JsonObject.props() = getValue("properties").jsonObject
    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `class becomes an object with required fields and descriptions`() {
        val schema = Schema.of(serializer<Event>().descriptor)
        assertEquals("object", schema.str("type"))
        assertEquals("string", schema.props().getValue("title").jsonObject.str("type"))
        assertEquals("Day and time", schema.props().getValue("time").jsonObject.str("description"))
        assertEquals("true", schema.props().getValue("place").jsonObject.str("nullable"))
        assertEquals("array", schema.props().getValue("bring").jsonObject.str("type"))
        assertEquals("integer", schema.props().getValue("attendees").jsonObject.str("type"))
        val required = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
        // Nullable and defaulted fields are optional.
        assertEquals(listOf("title", "time", "bring"), required)
    }

    @Test
    fun `nested classes and numbers`() {
        val schema = Schema.of(serializer<Receipt>().descriptor)
        assertEquals("number", schema.props().getValue("total").jsonObject.str("type"))
        val item = schema.props().getValue("items").jsonObject.getValue("items").jsonObject
        assertEquals("object", item.str("type"))
        assertEquals("number", item.props().getValue("price").jsonObject.str("type"))
    }

    @Test
    fun `enum lists its values`() {
        val schema = Schema.of(serializer<Mood>().descriptor)
        assertEquals("string", schema.str("type"))
        assertEquals(listOf("Positive", "Neutral", "Negative"), schema.getValue("enum").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `non-objects are wrapped in value for the model and unwrapped after`() {
        val listSchema = Schema.responseSchema(serializer<List<String>>().descriptor)
        assertEquals("object", listSchema.str("type"))
        assertEquals("array", listSchema.props().getValue("items").jsonObject.str("type"))
        assertEquals("string", Schema.responseSchema(serializer<Mood>().descriptor).props().getValue("answer").jsonObject.str("type"))

        assertEquals(listOf("Sure!", "On my way"), Schema.decodeResponse(serializer<List<String>>(), """{"items": ["Sure!", "On my way"]}"""))
        assertEquals(Mood.Negative, Schema.decodeResponse(serializer<Mood>(), """{"answer":"Negative"}"""))
        assertEquals(42, Schema.decodeResponse(serializer<Int>(), """{"answer": 42}"""))
        // A model that picks its own key still works.
        assertEquals(listOf("a", "b"), Schema.decodeResponse(serializer<List<String>>(), """{"replies": ["a", "b"]}"""))
        // Some models answer the bare value anyway.
        assertEquals(listOf("a"), Schema.decodeResponse(serializer<List<String>>(), """["a"]"""))
    }

    @Test
    fun `decoding tolerates fences, chatter, missing nullable fields and extra keys`() {
        val text = """
            Here you go:
            ```json
            {"title": "Midterm review", "time": "Thursday 4pm", "bring": ["calculator"], "mood": "happy"}
            ```
        """.trimIndent()
        val event = Schema.decodeResponse(serializer<Event>(), text)
        assertEquals("Midterm review", event.title)
        assertNull(event.place)
        assertEquals(listOf("calculator"), event.bring)
    }

    @Test
    fun `decoding drops thinking`() {
        val text = "<think>The user wants a reminder.</think>\n{\"title\":\"Water\",\"minutes\":1}"
        assertEquals(Reminder("Water", 1), Schema.decodeResponse(serializer<Reminder>(), text))
    }

    @Test
    fun `tool arguments from Gson decode whole doubles into Int`() {
        // Gson hands numbers over as Double.
        val json = Schema.toJson(mapOf("title" to "Call mom", "minutes" to 20.0))
        assertEquals(Reminder("Call mom", 20), Schema.json.decodeFromJsonElement(serializer<Reminder>(), json))
        assertEquals(JsonPrimitive(2.5), Schema.toJson(2.5))
        assertEquals(JsonPrimitive(3L), Schema.toJson(3))
    }

    @Test
    fun `tool results become plain values`() {
        val element = Schema.json.parseToJsonElement("""{"ok": true, "count": 2, "price": 1.5, "names": ["a"], "none": null}""")
        assertEquals(
            mapOf("ok" to true, "count" to 2L, "price" to 1.5, "names" to listOf("a"), "none" to null),
            Schema.toPlain(element),
        )
    }

    @Test
    fun `tool schema has name, description and parameters`() {
        val tool = Tool<Reminder>("set_reminder", "Remind the user later") { "ok" }
        assertEquals("set_reminder", tool.schema.str("name"))
        assertEquals("Remind the user later", tool.schema.str("description"))
        assertEquals("integer", tool.schema.getValue("parameters").jsonObject.props().getValue("minutes").jsonObject.str("type"))

        val noArgs = Tool("get_time", "Get the time") { "noon" }
        assertFalse(noArgs.schema.containsKey("parameters"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tool names must be identifiers`() {
        Tool("set reminder", "Spaces aren't allowed") { "ok" }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `tool arguments must be a class`() {
        Tool<String>("echo", "Echo") { it }
    }

    @Test
    fun `instructions show small models an example, not a schema`() {
        assertEquals(
            "Answer only with JSON like this:\n" +
                "{\"title\": \"...\", \"time\": \"...\", \"place\": \"...\" or null, \"bring\": [\"...\", ...], \"attendees\": 0}\n" +
                "- time: Day and time",
            Schema.instructions(serializer<Event>().descriptor),
        )
        assertEquals(
            "Answer only with JSON like this:\n{\"answer\": \"Positive | Neutral | Negative\"}",
            Schema.instructions(serializer<Mood>().descriptor),
        )
        assertEquals(
            "Answer only with JSON like this:\n{\"items\": [{\"name\": \"...\", \"price\": 0.0}, ...]}",
            Schema.instructions(serializer<List<Item>>().descriptor),
        )
    }

    @Test
    fun `model sizes read nicely`() {
        assertEquals("613 MB", Model.Qwen3.size)
        assertEquals("2.6 GB", Model.Gemma4.size)
        assertEquals("Qwen3-0.6B.litertlm", Model.Qwen3.fileName)
        assertTrue(Model.Gemma4.vision)
        assertFalse(Model.Qwen3.vision)
    }
}
