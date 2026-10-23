package io.github.al3j4ndrolm.facilis.ai

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.cancellation.CancellationException

/**
 * Owns the one loaded model. Calls take turns: a phone can only run one model conversation at a
 * time, so the last chat's conversation stays open and is rebuilt from history when another chat
 * needs the model in between.
 */
internal object Runtime {

    private const val TAG = "FacilisAI"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val downloads = mutableMapOf<Model, Deferred<Unit>>()

    private var engine: Engine? = null
    private var engineModel: Model? = null
    private var conversation: Conversation? = null
    private var conversationOwner: Any? = null
    private var idleJob: Job? = null

    val state = MutableStateFlow<AiState>(AiState.NotDownloaded)
    private var stateChecked = false

    private val context: Context get() = Tracker.appContext

    fun file(model: Model): File =
        model.path?.let(::File) ?: File(context.noBackupFilesDir, "facilis_ai/${model.fileName}")

    fun isDownloaded(model: Model): Boolean = file(model).isFile

    /** Brings [state] up to date with what's on disk, e.g. after the model is changed. */
    fun refreshState() {
        stateChecked = true
        val model = FacilisAI.model
        if (state.value is AiState.Downloading || state.value is AiState.Loading) return
        state.value = when {
            engine != null && engineModel == model -> AiState.Ready
            isDownloaded(model) -> AiState.Downloaded
            else -> AiState.NotDownloaded
        }
    }

    fun checkedState() = state.also { if (!stateChecked) refreshState() }

    suspend fun download(model: Model, wifiOnly: Boolean) {
        if (isDownloaded(model)) return
        if (model.url == null) throw IOException("Model file not found: ${model.path}")
        if (wifiOnly && isMetered()) throw IOException("Not on Wi-Fi: ${model.name} (${model.size}) waits for Wi-Fi")
        val job = synchronized(downloads) {
            downloads.getOrPut(model) {
                scope.async {
                    try {
                        Downloader.download(model, file(model)) { done, total ->
                            state.value = AiState.Downloading(if (total > 0) done.toFloat() / total else 0f, done, total)
                        }
                        state.value = AiState.Downloaded
                    } catch (e: CancellationException) {
                        // Paused: the partial file stays, and download() continues from it.
                        state.value = AiState.NotDownloaded
                        throw e
                    } catch (e: Exception) {
                        state.value = AiState.Failed(e)
                        throw e
                    } finally {
                        synchronized(downloads) { downloads.remove(model) }
                    }
                }
            }
        }
        job.await()
    }

    fun cancelDownload() {
        synchronized(downloads) { downloads.values.toList() }.forEach { it.cancel() }
    }

    fun delete(model: Model) {
        scope.launch {
            mutex.withLock {
                if (engineModel == model) closeEngine()
                file(model).delete()
                File(file(model).path + ".part").delete()
                refreshState()
            }
        }
    }

    /**
     * Runs [block] with a conversation belonging to [owner], creating one from [config] if the
     * owner doesn't have the open one. [temporary] conversations are closed right after.
     */
    suspend fun <R> withConversation(
        owner: Any,
        temporary: Boolean,
        config: () -> ConversationConfig,
        block: suspend (Conversation) -> R,
    ): R {
        val model = FacilisAI.model
        if (!isDownloaded(model)) {
            if (!FacilisAI.autoDownload) throw IllegalStateException(
                "${model.name} isn't downloaded yet. Call ai.download() first, or set ai.autoDownload = true."
            )
            download(model, wifiOnly = false)
        }
        return mutex.withLock {
            idleJob?.cancel()
            try {
                val engine = withContext(Dispatchers.IO) { loadEngine(model) }
                var current = conversation
                if (conversationOwner !== owner || current == null || !current.isAlive) {
                    closeConversation()
                    current = withContext(Dispatchers.IO) { engine.createConversation(config()) }
                    conversation = current
                    conversationOwner = owner
                }
                try {
                    block(current)
                } catch (e: Throwable) {
                    // A failed or cancelled reply leaves the conversation half-written: start fresh next time.
                    if (e is CancellationException) runCatching { current.cancelProcess() }
                    closeConversation()
                    throw e
                } finally {
                    if (temporary) closeConversation()
                }
            } finally {
                scheduleIdleUnload()
            }
        }
    }

    /** Forgets [owner]'s open conversation, e.g. when a chat is cleared. */
    fun release(owner: Any) {
        scope.launch {
            mutex.withLock { if (conversationOwner === owner) closeConversation() }
        }
    }

    /** Loads the model into memory ahead of time, so the first answer is quick. */
    suspend fun prepare() {
        val model = FacilisAI.model
        download(model, wifiOnly = false)
        mutex.withLock {
            withContext(Dispatchers.IO) { loadEngine(model) }
            scheduleIdleUnload()
        }
    }

    fun unload() {
        scope.launch { mutex.withLock { closeEngine() } }
    }

    fun unloadWhenIdle() {
        scope.launch {
            if (mutex.tryLock()) {
                try {
                    closeEngine()
                } finally {
                    mutex.unlock()
                }
            }
        }
    }

    // Callers hold the mutex.
    private fun loadEngine(model: Model): Engine {
        engine?.let { if (engineModel == model) return it }
        closeEngine()
        state.value = AiState.Loading
        val path = file(model).absolutePath
        val backends = if (FacilisAI.useGpu) listOf(Backend.GPU(), Backend.CPU()) else listOf(Backend.CPU())
        var lastError: Throwable? = null
        for (backend in backends) {
            try {
                val started = System.currentTimeMillis()
                val created = Engine(
                    EngineConfig(
                        modelPath = path,
                        backend = backend,
                        visionBackend = if (model.vision) backend else null,
                        maxNumTokens = model.contextTokens,
                        cacheDir = context.cacheDir.absolutePath,
                    )
                )
                created.initialize()
                Log.i(TAG, "Loaded ${model.name} on ${backend.name} in ${System.currentTimeMillis() - started} ms")
                engine = created
                engineModel = model
                state.value = AiState.Ready
                return created
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                Log.w(TAG, "Couldn't load ${model.name} on ${backend.name}: ${e.message}")
                lastError = e
            }
        }
        val error = IllegalStateException("Couldn't load ${model.name}: ${lastError?.message}", lastError)
        state.value = AiState.Failed(error)
        throw error
    }

    private fun closeConversation() {
        runCatching { conversation?.close() }
        conversation = null
        conversationOwner = null
    }

    private fun closeEngine() {
        closeConversation()
        val closing = engine ?: return
        runCatching { closing.close() }
        engine = null
        engineModel = null
        Log.i(TAG, "Unloaded the model")
        refreshState()
    }

    private fun scheduleIdleUnload() {
        idleJob?.cancel()
        val keep = FacilisAI.keepLoaded
        if (keep.isInfinite()) return
        idleJob = scope.launch {
            delay(keep)
            mutex.withLock { closeEngine() }
        }
    }

    private fun isMetered(): Boolean =
        (context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).isActiveNetworkMetered
}
