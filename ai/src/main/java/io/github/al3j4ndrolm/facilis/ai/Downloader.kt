package io.github.al3j4ndrolm.facilis.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Downloads a model file, resuming a partial download if one exists. */
internal object Downloader {

    private const val BUFFER = 1 shl 20

    /**
     * Downloads [model] to [dest]. [onProgress] gets (downloaded, total) bytes, total 0 if unknown.
     * The file only appears at [dest] once it's complete and verified.
     */
    suspend fun download(model: Model, dest: File, onProgress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val url = checkNotNull(model.url) { "${model.name} has no download url" }
        dest.parentFile?.mkdirs()
        val part = File(dest.path + ".part")

        var downloaded = part.length()
        if (model.sizeBytes > 0 && downloaded > model.sizeBytes) {
            part.delete()
            downloaded = 0
        }

        if (model.sizeBytes <= 0 || downloaded < model.sizeBytes) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                if (downloaded > 0) setRequestProperty("Range", "bytes=$downloaded-")
            }
            try {
                val append = when (connection.responseCode) {
                    HttpURLConnection.HTTP_PARTIAL -> true
                    HttpURLConnection.HTTP_OK -> false
                    else -> throw IOException("Download failed: HTTP ${connection.responseCode} for $url")
                }
                if (!append) downloaded = 0
                val total = if (model.sizeBytes > 0) model.sizeBytes else connection.contentLengthLong.let {
                    if (it > 0) it + downloaded else 0
                }
                onProgress(downloaded, total)

                connection.inputStream.use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buffer = ByteArray(BUFFER)
                        var lastReport = 0L
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastReport >= 4 * BUFFER) {
                                lastReport = downloaded
                                onProgress(downloaded, total)
                            }
                        }
                    }
                }
                onProgress(downloaded, total)
            } finally {
                connection.disconnect()
            }
        }

        if (model.sizeBytes > 0 && part.length() != model.sizeBytes) {
            throw IOException("Download incomplete: got ${part.length()} of ${model.sizeBytes} bytes. Call download() again to resume.")
        }
        model.sha256?.let { expected ->
            val actual = sha256(part)
            if (actual != expected) {
                part.delete()
                throw IOException("Downloaded ${model.name} is corrupted (sha256 $actual, expected $expected). Deleted it; try again.")
            }
        }
        if (!part.renameTo(dest)) throw IOException("Couldn't move the model into place at $dest")
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
