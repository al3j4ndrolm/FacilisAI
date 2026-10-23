package io.github.al3j4ndrolm.facilis.ai

import android.net.Uri
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ThinkingConfig
import com.google.ai.edge.litertlm.ToolCall
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.update

/** One line of a [Chat], for showing it on screen. */
data class ChatMessage(
    val role: Role,
    val text: String,
    val image: Uri? = null,
    /** For [Role.Tool]: the tool that ran. */
    val tool: String? = null,
) {
    enum class Role { User, AI, Tool }
}

/**
 * A conversation that remembers what was said and can use tools:
 *
 * ```
 * val chat = ai.chat(system = "You are a study buddy")
 * chat.send("Quiz me on CS 149")
 * chat.send("Harder")
 * ```
 *
 * Show it with `val messages by chat.messages.collectAsState()`.
 */
class Chat internal constructor(
    /** Instructions the model follows for the whole chat. */
    val system: String?,
    /** What the model can do. When it calls one, the chat runs it and lets the model continue. */
    val tools: List<Tool<*>>,
    private val maxSteps: Int,
) {
    init {
        require(maxSteps >= 1) { "maxSteps must be at least 1" }
        val duplicate = tools.groupBy { it.name }.entries.firstOrNull { it.value.size > 1 }
        require(duplicate == null) { "Two tools are named \"${duplicate?.key}\"" }
    }

    private val history = mutableListOf<Message>()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    private val _working = MutableStateFlow(false)

    /** Everything said so far, including tools that ran. Updates live while the model writes. */
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    /** Whether the model is writing (or running a tool) right now. */
    val working: StateFlow<Boolean> = _working.asStateFlow()

    /** Sends [text] (and optionally a picture) and returns the reply once it's complete. */
    suspend fun send(text: String, image: Uri? = null): String = stream(text, image).fold("") { all, chunk -> all + chunk }

    /** Sends [text] and returns the reply as it's written, a few words at a time. */
    fun stream(text: String, image: Uri? = null): Flow<String> = flow {
        // The instructions go in the first message rather than a system turn: some models' chat
        // templates (Qwen3's, with tools) can't render a system turn.
        val first = history.isEmpty() && system != null
        val userContents = Images.contents(if (first) "$system\n\n$text" else text, image)
        val savedHistory = history.size
        val savedMessages = _messages.value
        _working.value = true
        try {
            Runtime.withConversation(this@Chat, temporary = false, ::config) { conversation ->
                _messages.update { it + ChatMessage(ChatMessage.Role.User, text, image) }
                var message = Message.user(userContents)
                history += message
                for (step in 1..maxSteps) {
                    val filter = Thinking.Filter()
                    val reply = StringBuilder()
                    val calls = mutableListOf<ToolCall>()
                    var shown = false
                    suspend fun show(chunk: String) {
                        if (chunk.isEmpty()) return
                        reply.append(chunk)
                        emit(chunk)
                        val line = ChatMessage(ChatMessage.Role.AI, reply.toString())
                        _messages.update { if (shown) it.dropLast(1) + line else it + line }
                        shown = true
                    }
                    conversation.sendMessageAsync(message).collect { part ->
                        calls += part.toolCalls
                        show(filter.push(part.text()))
                    }
                    show(filter.finish())

                    history += Message.model(Contents.of(reply.toString()), calls)
                    if (calls.isEmpty()) return@withConversation
                    if (step == maxSteps) {
                        show("(Stopped after $maxSteps steps.)")
                        return@withConversation
                    }

                    val responses = calls.map { call ->
                        val tool = tools.firstOrNull { it.name == call.name }
                        val result = tool?.run(call.arguments)
                            ?: Tool.Result(false, "Error: there's no tool named ${call.name}")
                        _messages.update {
                            it + ChatMessage(ChatMessage.Role.Tool, describe(call, tool, result), tool = call.name)
                        }
                        Content.ToolResponse(call.name, result.value)
                    }
                    message = Message.tool(Contents.of(responses))
                    history += message
                }
            }
        } catch (e: Throwable) {
            // Leave the chat as it was before this message, so it can simply be sent again.
            while (history.size > savedHistory) history.removeAt(history.lastIndex)
            _messages.value = savedMessages
            throw e
        } finally {
            _working.value = false
        }
    }

    /** Forgets the conversation and starts over. */
    fun clear() {
        history.clear()
        _messages.value = emptyList()
        Runtime.release(this)
    }

    private fun config() = ConversationConfig(
        // Re-opening a chat after another one used the model: replay what was said.
        initialMessages = history.toList(),
        tools = tools.map { it.provider },
        automaticToolCalling = false,
        thinkingConfig = ThinkingConfig(enableThinking = FacilisAI.thinking),
    )

    private fun describe(call: ToolCall, tool: Tool<*>?, result: Tool.Result): String {
        val shown = if (tool?.takesArguments == false) emptyMap() else call.arguments
        val arguments = shown.entries.joinToString(", ") { (key, value) -> "$key=${Schema.toJson(value)}" }
        return "${call.name}($arguments) → ${result.value}"
    }
}

internal fun Message.text(): String =
    contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
