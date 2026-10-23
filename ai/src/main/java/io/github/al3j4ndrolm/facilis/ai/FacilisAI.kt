package io.github.al3j4ndrolm.facilis.ai

import android.net.Uri
import android.util.Log
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.ResponseFormat
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.ThinkingConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * AI that runs on the phone: offline, private and free. Available everywhere:
 *
 * ```
 * val summary = ai("Summarize this: $notes")
 * val receipt = ai<Receipt>("Extract the receipt: $text")
 * ```
 */
val ai: FacilisAI get() = FacilisAI

object FacilisAI {

    // region Setup

    /**
     * The model to run. Change it before the first call (or call [unload] after changing it).
     * Defaults to [Model.Qwen3], about 500 MB.
     */
    @JvmStatic
    var model: Model = Model.Default
        set(value) {
            field = value
            Runtime.refreshState()
        }

    /** Download the model automatically on the first call. Turn off to control it with [download]. */
    @JvmStatic
    var autoDownload: Boolean = true

    /** Run on the GPU when the phone supports it (much faster), falling back to the CPU. */
    @JvmStatic
    var useGpu: Boolean = true

    /** Let models that can "think" reason before answering: sometimes better, always slower. */
    @JvmStatic
    var thinking: Boolean = false

    /** How long the model stays in memory after the last call. Loading it again takes a few seconds. */
    @JvmStatic
    var keepLoaded: Duration = 5.minutes

    /** Tools [agent] and new [chat]s can use. Add with [tool]. */
    @JvmStatic
    val tools: MutableList<Tool<*>> = mutableListOf()

    /**
     * Decides whether a `confirm = true` tool may run. By default the user is asked with a dialog
     * (and it's refused if your app isn't on screen).
     */
    @JvmStatic
    var onConfirm: (suspend (tool: Tool<*>, arguments: Map<String, Any?>) -> Boolean)? = null

    // endregion

    // region Model

    /** Where the model is: downloading, loading, ready... */
    @JvmStatic
    val state: StateFlow<AiState> get() = Runtime.checkedState()

    /** Whether the [model] is on the phone. */
    @JvmStatic
    val isDownloaded: Boolean get() = Runtime.isDownloaded(model)

    /**
     * Downloads the [model] now (resuming a previous attempt), so the first call doesn't wait.
     * Follow it with [state]. Keeps going if the caller is cancelled; use [cancelDownload] to stop.
     *
     * @param wifiOnly throws instead of downloading on mobile data.
     */
    @JvmStatic
    suspend fun download(wifiOnly: Boolean = false) = Runtime.download(model, wifiOnly)

    /** Stops a download. Calling [download] later continues where it stopped. */
    @JvmStatic
    fun cancelDownload() = Runtime.cancelDownload()

    /** Downloads (if needed) and loads the model into memory, so the next answer starts right away. */
    @JvmStatic
    suspend fun prepare() = Runtime.prepare()

    /** Frees the memory the model uses. It loads again on the next call. */
    @JvmStatic
    fun unload() = Runtime.unload()

    /** Deletes a downloaded model from the phone to free its space. */
    @JvmStatic
    fun deleteModel(model: Model = this.model) = Runtime.delete(model)

    // endregion

    // region Ask

    /**
     * Asks the model and returns its answer. To let it use your tools, call [agent] instead.
     *
     * @param image a picture to ask about (needs a model with vision, like [Model.Gemma4]).
     * @param system instructions for how to answer, e.g. "Answer in Spanish".
     */
    suspend operator fun invoke(prompt: String, image: Uri? = null, system: String? = null): String =
        chat(system, tools = emptyList()).send(prompt, image)

    /**
     * Asks the model for a typed answer. The model is constrained to [T]'s shape, so you get
     * an object, not text to parse:
     *
     * ```
     * @Serializable data class Receipt(val store: String, val total: Double)
     * val receipt = ai<Receipt>("Extract the receipt: $text")
     * ```
     *
     * [T] can be a `@Serializable` class, an enum, a `List`, or a String/number/Boolean.
     */
    @JvmName("invokeTyped")
    suspend inline operator fun <reified T> invoke(prompt: String, image: Uri? = null, system: String? = null): T =
        ask(serializer<T>(), prompt, image, system)

    /**
     * Asks the model to do something with the [tools] you added. It calls them as needed
     * (several in a row if it must) and then answers:
     *
     * ```
     * ai.agent("Remind me to call mom in 20 minutes")   // runs your set_reminder tool
     * ```
     *
     * Plain [invoke] never uses tools, so questions get plain answers.
     */
    @JvmStatic
    suspend fun agent(prompt: String, image: Uri? = null, system: String? = null, maxSteps: Int = 6): String {
        check(tools.isNotEmpty()) { "Add a tool first with ai.tool(...)" }
        return chat(system, maxSteps = maxSteps).send(prompt, image)
    }

    /** Like [invoke], but returns the answer as it's written, a few words at a time. */
    @JvmStatic
    fun stream(prompt: String, image: Uri? = null, system: String? = null): Flow<String> =
        chat(system, tools = emptyList()).stream(prompt, image)

    /**
     * Starts a conversation that remembers what was said.
     *
     * @param tools what the model can do in this chat (defaults to the ones added with [tool]).
     * @param maxSteps how many times in a row the model may use tools before it must answer.
     */
    @JvmStatic
    fun chat(system: String? = null, tools: List<Tool<*>> = this.tools.toList(), maxSteps: Int = 6): Chat =
        Chat(system, tools, maxSteps)

    /**
     * Adds a tool every call can use:
     *
     * ```
     * @Serializable data class Reminder(val title: String, val minutes: Int)
     *
     * ai.tool<Reminder>("set_reminder", "Remind the user later") { r ->
     *     notify.after(r.minutes.minutes, r.title)
     *     "Reminder set"
     * }
     * ai.agent("Remind me to call mom in 20 minutes")
     * ```
     */
    inline fun <reified T> tool(
        name: String,
        description: String,
        confirm: Boolean = false,
        noinline handler: suspend (T) -> Any?,
    ): Tool<T> = Tool<T>(name, description, confirm, handler).also(::add)

    /** Adds a tool with no arguments, e.g. "get_battery_level". */
    @JvmStatic
    fun tool(name: String, description: String, confirm: Boolean = false, handler: suspend () -> Any?): Tool<Unit> =
        Tool(name, description, confirm, handler).also(::add)

    /** Removes a tool added with [tool]. */
    @JvmStatic
    fun removeTool(name: String) {
        tools.removeAll { it.name == name }
    }

    @PublishedApi
    internal fun add(tool: Tool<*>) {
        removeTool(tool.name)
        tools += tool
    }

    @PublishedApi
    internal suspend fun <T> ask(serializer: KSerializer<T>, prompt: String, image: Uri?, system: String?): T {
        val schema = Schema.responseSchema(serializer.descriptor)
        val instructions = system?.let { "$it\n\n" }.orEmpty()
        val contents = Images.contents("$instructions$prompt\n\n${Schema.instructions(serializer.descriptor)}", image)
        val config = ConversationConfig(
            // Extraction wants the most likely answer, not creativity.
            samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0),
            thinkingConfig = ThinkingConfig(enableThinking = false),
            enableResponseFormat = true,
        )
        suspend fun answer(format: ResponseFormat?) = Runtime.withConversation(Any(), temporary = true, { config }) {
            withContext(Dispatchers.IO) { it.sendMessage(contents, responseFormat = format).text() }
        }

        // Free-form first: constrained decoding guarantees the shape but makes models cut lists short.
        val free = answer(null)
        decodeOrNull(serializer, free)?.let { return it }
        Log.d(TAG, "Answer didn't fit ${serializer.descriptor.serialName}, retrying constrained: $free")
        val constrained = answer(ResponseFormat.json(schema.toString()))
        return decodeOrNull(serializer, constrained)
            ?: throw IllegalStateException("The model's answer didn't fit ${serializer.descriptor.serialName}: $constrained")
    }

    private fun <T> decodeOrNull(serializer: KSerializer<T>, text: String): T? = try {
        Schema.decodeResponse(serializer, text)
    } catch (e: SerializationException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    private const val TAG = "FacilisAI"

    // endregion
}
