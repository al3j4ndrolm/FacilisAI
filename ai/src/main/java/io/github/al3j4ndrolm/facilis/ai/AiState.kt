package io.github.al3j4ndrolm.facilis.ai

/**
 * Where the model is, for showing a download bar or a "warming up" label:
 *
 * ```
 * val state by ai.state.collectAsState()
 * (state as? AiState.Downloading)?.let { LinearProgressIndicator({ it.progress }) }
 * ```
 */
sealed interface AiState {
    /** Not on the phone yet. The first call downloads it, or call `ai.download()` yourself. */
    data object NotDownloaded : AiState

    /** Downloading. [progress] goes from 0 to 1 (0 while the size is unknown). */
    data class Downloading(val progress: Float, val downloadedBytes: Long, val totalBytes: Long) : AiState

    /** On the phone, loaded into memory on the next call (a few seconds). */
    data object Downloaded : AiState

    /** Loading into memory. */
    data object Loading : AiState

    /** Loaded and ready to answer right away. */
    data object Ready : AiState

    /** The last download or load failed. Calling again retries (downloads resume where they stopped). */
    data class Failed(val error: Throwable) : AiState
}
