package io.github.al3j4ndrolm.facilis.ai.sample

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.al3j4ndrolm.facilis.ai.AiState
import io.github.al3j4ndrolm.facilis.ai.ChatMessage
import io.github.al3j4ndrolm.facilis.ai.Describe
import io.github.al3j4ndrolm.facilis.ai.Model
import io.github.al3j4ndrolm.facilis.ai.ai
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

private const val TAG = "FacilisAISample"

@Serializable
data class Event(
    val title: String,
    @Describe("Day and time, as written") val time: String,
    val place: String?,
    @Describe("Things to bring") val bring: List<String>,
)

@Serializable
enum class Mood { Positive, Neutral, Negative }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) { SampleScreen() }
            }
        }
    }
}

/** Runs [block], logging how long it took and any error, and shows the result. */
private fun CoroutineScope.timed(label: String, output: (String) -> Unit, block: suspend () -> String) = launch {
    val started = System.currentTimeMillis()
    output("…")
    val result = try {
        block()
    } catch (e: Exception) {
        Log.e(TAG, "$label failed", e)
        "Error: ${e.message}"
    }
    val seconds = (System.currentTimeMillis() - started) / 1000.0
    Log.i(TAG, "$label (${"%.1f".format(seconds)} s): $result")
    output("$result\n(${"%.1f".format(seconds)} s)")
}

@Composable
private fun SampleScreen() {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("FacilisAI", style = MaterialTheme.typography.headlineMedium)

        // Model
        var model by remember { mutableStateOf(ai.model) }
        val state by ai.state.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(Model.Qwen3, Model.Gemma4).forEach { option ->
                FilterChip(
                    selected = model == option,
                    onClick = { ai.unload(); ai.model = option; model = option },
                    label = { Text("${option.name} · ${option.size}") },
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = when (val s = state) {
                    is AiState.Downloading -> "Downloading ${(s.progress * 100).toInt()}%"
                    is AiState.Failed -> "Failed: ${s.error.message}"
                    else -> s.toString()
                },
                modifier = Modifier.weight(1f).semantics { contentDescription = "state" },
            )
            when (state) {
                AiState.NotDownloaded, is AiState.Failed ->
                    TextButton(onClick = { scope.launch { runCatching { ai.download() } } }) { Text("Download") }
                is AiState.Downloading -> TextButton(onClick = { ai.cancelDownload() }) { Text("Pause") }
                else -> TextButton(onClick = { ai.deleteModel() }) { Text("Delete") }
            }
        }
        (state as? AiState.Downloading)?.let { LinearProgressIndicator({ it.progress }, Modifier.fillMaxWidth()) }

        // 1. Ask, streaming
        HorizontalDivider()
        Section("Ask")
        var question by remember { mutableStateOf("Give me 3 tips to study for a midterm, one line each.") }
        var answer by remember { mutableStateOf("") }
        OutlinedTextField(question, { question = it }, Modifier.fillMaxWidth())
        Button(
            onClick = {
                scope.timed("ask", { answer = it }) {
                    var text = ""
                    ai.stream(question).collect { text += it; answer = text }
                    text
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("ai.stream(question)") }
        Output(answer, "answer")

        // 2. Typed answers
        HorizontalDivider()
        Section("Extract")
        var message by remember { mutableStateOf("midterm review thursday 4pm in MQH 225, bring a calculator and your notes!") }
        var extracted by remember { mutableStateOf("") }
        OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                scope.timed("extract", { extracted = it }) { ai<Event>("Extract the event from: $message").toString() }
            }) { Text("ai<Event>") }
            Button(onClick = {
                scope.timed("mood", { extracted = it }) { ai<Mood>("Classify the mood of: $message").toString() }
            }) { Text("ai<Mood>") }
            Button(onClick = {
                scope.timed("replies", { extracted = it }) {
                    ai<List<String>>("Suggest 3 short replies to this message: $message").joinToString("\n")
                }
            }) { Text("Replies") }
        }
        Output(extracted, "extracted")

        // 3. Agent: tools from SampleApp (FacilisNotification + FacilisStorage)
        HorizontalDivider()
        Section("Agent")
        val chat = remember { ai.chat(system = "You are a helpful assistant inside a student app. Use tools when they help.") }
        var request by remember { mutableStateOf("Remind me to drink water in 1 minute, and save a note that the quiz covers chapter 4") }
        OutlinedTextField(request, { request = it }, Modifier.fillMaxWidth())
        val working by chat.working.collectAsState()
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = { scope.timed("agent", {}) { chat.send(request) } },
                enabled = !working,
            ) { Text(if (working) "Working…" else "chat.send") }
            OutlinedButton(onClick = { chat.clear() }) { Text("Clear") }
        }
        val messages by chat.messages.collectAsState()
        messages.forEach { line ->
            val who = when (line.role) {
                ChatMessage.Role.User -> "You"
                ChatMessage.Role.AI -> "AI"
                ChatMessage.Role.Tool -> "🔧"
            }
            Text("$who: ${line.text}", style = MaterialTheme.typography.bodySmall)
        }

        // 4. Photos (vision models)
        HorizontalDivider()
        Section("Photo")
        var photoAnswer by remember { mutableStateOf("") }
        var photo by remember { mutableStateOf<Uri?>(null) }
        val picker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
            photo = uri
            if (uri != null) scope.timed("photo", { photoAnswer = it }) { ai("What is in this picture? One sentence.", image = uri) }
        }
        Button(
            onClick = { picker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
            enabled = model.vision,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (model.vision) "Pick a photo and ask about it" else "Needs ${Model.Gemma4.name}") }
        val context = LocalContext.current
        Button(
            onClick = {
                // A whiteboard photo bundled with the sample: photo -> typed data.
                val file = File(context.cacheDir, "whiteboard.jpg")
                context.assets.open("whiteboard.jpg").use { input -> file.outputStream().use { input.copyTo(it) } }
                photo = Uri.fromFile(file)
                scope.timed("whiteboard", { photoAnswer = it }) {
                    ai<Event>("Extract the event written on this whiteboard", image = Uri.fromFile(file)).toString()
                }
            },
            enabled = model.vision,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Whiteboard photo → ai<Event>") }
        Output(photoAnswer, "photo")
    }
}

@Composable
private fun Section(title: String) = Text(title, style = MaterialTheme.typography.titleMedium)

@Composable
private fun Output(text: String, label: String) {
    if (text.isNotEmpty()) Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.semantics { contentDescription = label })
}
