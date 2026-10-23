package io.github.al3j4ndrolm.facilis.ai

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingTest {

    private fun streamed(vararg chunks: String): String {
        val filter = Thinking.Filter()
        return chunks.joinToString("") { filter.push(it) } + filter.finish()
    }

    @Test
    fun `strip removes reasoning blocks`() {
        assertEquals("\n\nHello", Thinking.strip("<think>hmm</think>\n\nHello"))
        assertEquals("Hello", Thinking.strip("Hello"))
        assertEquals("A", Thinking.strip("A<think>unfinished"))
        assertEquals("Hi", Thinking.strip("</think>Hi"))
    }

    @Test
    fun `filter hides reasoning split across chunks`() {
        assertEquals("Hello there", streamed("<th", "ink>Let me ", "think</th", "ink>\n\nHello", " there"))
    }

    @Test
    fun `filter passes normal text, including lone angle brackets`() {
        assertEquals("1 < 2 and <b> stays", streamed("1 < 2", " and <b>", " stays"))
        assertEquals("Answer", streamed("Ans", "wer"))
    }

    @Test
    fun `filter drops empty reasoning that models emit when thinking is off`() {
        assertEquals("Sure!", streamed("<think>\n\n</think>\n\n", "Sure!"))
    }

    @Test
    fun `filter holds back a possible tag only until it can tell`() {
        val filter = Thinking.Filter()
        assertEquals("Hi ", filter.push("Hi <"))
        assertEquals("<3", filter.push("3"))
    }

    @Test
    fun `tool run reports bad arguments to the model instead of throwing`() = runTest {
        val tool = Tool<SchemaTest.Reminder>("set_reminder", "Remind") { "ok" }
        val result = tool.run(mapOf("title" to "Water"))
        assertTrue(!result.ok && result.value.toString().startsWith("Error: invalid arguments"))
        val good = tool.run(mapOf("title" to "Water", "minutes" to 1.0))
        assertTrue(good.ok)
        assertEquals("ok", good.value)
    }

    @Test
    fun `tool errors go back to the model`() = runTest {
        val tool = Tool("fails", "Always fails") { error("disk full") }
        val result = tool.run(emptyMap())
        assertEquals("Error: fails failed: disk full", result.value)
    }
}
