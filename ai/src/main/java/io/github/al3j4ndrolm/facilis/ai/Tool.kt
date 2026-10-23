package io.github.al3j4ndrolm.facilis.ai

import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.ToolProvider
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import com.google.ai.edge.litertlm.tool as liteRtTool

/**
 * Something the model can do in your app. The arguments are a `@Serializable` class, so the model
 * fills them in for you:
 *
 * ```
 * @Serializable data class Reminder(val title: String, val minutes: Int)
 *
 * val reminder = Tool<Reminder>("set_reminder", "Remind the user later") { r ->
 *     notify.after(r.minutes.minutes, r.title)
 *     "Reminder set"
 * }
 * ```
 *
 * Whatever the handler returns (text, numbers, a map or list) is shown to the model.
 */
class Tool<T> @PublishedApi internal constructor(
    /** The name the model calls it by, e.g. "set_reminder". */
    val name: String,
    /** What it does, for the model. Be specific: this is how it decides when to use it. */
    val description: String,
    private val serializer: KSerializer<T>?,
    /** Ask the user before running it (for anything that deletes, sends or spends). */
    val confirm: Boolean,
    private val handler: suspend (T) -> Any?,
) {
    init {
        require(name.matches(Regex("[A-Za-z_][A-Za-z0-9_]{0,63}"))) {
            "Tool name \"$name\" must be letters, digits and underscores, like \"set_reminder\""
        }
        serializer?.let {
            require(Schema.isObject(it.descriptor)) { "Tool \"$name\": arguments must be a @Serializable class" }
        }
    }

    internal val schema: JsonObject = buildJsonObject {
        put("name", name)
        put("description", description)
        serializer?.let { put("parameters", Schema.of(it.descriptor)) }
    }

    internal val provider: ToolProvider = liteRtTool(object : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = schema.toString()

        // FacilisAI runs tools itself (to support suspend handlers and confirmation).
        override fun execute(paramsJsonString: String): String = error("Tools are run by FacilisAI")
    })

    /** Runs the tool for the model. Problems go back to the model as text so it can correct itself. */
    internal suspend fun run(arguments: Map<String, Any?>): Result {
        @Suppress("UNCHECKED_CAST")
        val input = try {
            if (serializer == null) Unit as T else Schema.json.decodeFromJsonElement(serializer, Schema.toJson(arguments))
        } catch (e: Exception) {
            return Result(false, "Error: invalid arguments for $name (${e.message?.lineSequence()?.firstOrNull()}). Try again.")
        }
        // Arguments a tool doesn't take are made up by the model: don't show them to the user.
        val shown = if (serializer == null) emptyMap() else arguments
        if (confirm && !Confirm.ask(this, shown)) {
            return Result(false, "Not done: the user said no. Tell them you didn't do it, and don't try again.")
        }
        return try {
            Result(true, handler(input) ?: "Done")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result(false, "Error: $name failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    internal class Result(val ok: Boolean, val value: Any)

    internal val takesArguments: Boolean get() = serializer != null

    override fun toString(): String = "Tool($name)"

    companion object {
        /** A tool whose arguments are the `@Serializable` class [T]. */
        inline operator fun <reified T> invoke(
            name: String,
            description: String,
            confirm: Boolean = false,
            noinline handler: suspend (T) -> Any?,
        ): Tool<T> = Tool(name, description, serializer<T>(), confirm, handler)

        /** A tool with no arguments, e.g. "get_battery_level". */
        @JvmName("noArguments")
        operator fun invoke(
            name: String,
            description: String,
            confirm: Boolean = false,
            handler: suspend () -> Any?,
        ): Tool<Unit> = Tool(name, description, null, confirm) { handler() }
    }
}
