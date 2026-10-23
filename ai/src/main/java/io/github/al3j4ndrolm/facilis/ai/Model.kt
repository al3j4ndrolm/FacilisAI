package io.github.al3j4ndrolm.facilis.ai

import java.io.File

/**
 * A language model that runs on the phone. Pick one before the first call:
 *
 * ```
 * ai.model = Model.Gemma4     // smarter, understands images
 * ```
 *
 * Models are downloaded once into your app's private storage (not bundled in your APK).
 */
class Model private constructor(
    /** A short name, e.g. "Qwen3 0.6B". */
    val name: String,
    internal val url: String?,
    internal val path: String?,
    /** The download size in bytes (0 if unknown). */
    val sizeBytes: Long,
    internal val sha256: String?,
    /** Whether the model understands images. */
    val vision: Boolean,
    /** How many tokens (about 3/4 of a word each) a conversation can hold. */
    val contextTokens: Int,
) {
    internal val fileName: String
        get() = path?.let { File(it).name } ?: url!!.substringAfterLast('/').substringBefore('?')

    /** A short "500 MB" style size for showing users before they download. */
    val size: String
        get() = when {
            sizeBytes <= 0 -> "unknown size"
            sizeBytes >= 1_000_000_000 -> "%.1f GB".format(sizeBytes / 1e9)
            else -> "${sizeBytes / 1_000_000} MB"
        }

    override fun toString(): String = "Model($name, $size)"

    override fun equals(other: Any?): Boolean = other is Model && other.url == url && other.path == path

    override fun hashCode(): Int = (url ?: path).hashCode()

    companion object {
        private const val HF = "https://huggingface.co/litert-community"

        /**
         * Qwen3 0.6B by Alibaba (Apache 2.0). About 600 MB, text only.
         * The smallest download: good for summaries, extraction, classification and a few tools.
         */
        @JvmField
        val Qwen3 = Model(
            name = "Qwen3 0.6B",
            url = "$HF/Qwen3-0.6B/resolve/e6687177e00897731e7716267eebd4eba0dde7c2/Qwen3-0.6B.litertlm",
            path = null,
            sizeBytes = 613_722_264,
            sha256 = "c5f39b4191926786c3e6668b62af4d6eb15248dfc7a5348252a9b406f33f2108",
            vision = false,
            contextTokens = 2048,
        )

        /**
         * Gemma 4 E2B by Google (Apache 2.0). About 2 GB, understands images.
         * Noticeably smarter, especially at picking tools and following instructions.
         */
        @JvmField
        val Gemma4 = Model(
            name = "Gemma 4 E2B",
            url = "$HF/gemma-4-E2B-it-litert-lm/resolve/a790a852d433991c0c0a41c1154cf9b3850300fa/gemma-4-E2B-it.litertlm",
            path = null,
            sizeBytes = 2_588_147_712,
            sha256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c",
            vision = true,
            contextTokens = 4096,
        )

        /** The model used unless you pick another. */
        @JvmField
        val Default = Qwen3

        /**
         * Any `.litertlm` model you host yourself (e.g. on your own server or a Hugging Face repo).
         *
         * @param sha256 if given, the download is checked against it.
         */
        @JvmStatic
        fun url(
            url: String,
            name: String = url.substringAfterLast('/'),
            sizeBytes: Long = 0,
            sha256: String? = null,
            vision: Boolean = false,
            contextTokens: Int = 4096,
        ): Model = Model(name, url, null, sizeBytes, sha256?.lowercase(), vision, contextTokens)

        /** A `.litertlm` model already on the device, e.g. pushed with adb while developing. */
        @JvmStatic
        fun file(
            path: String,
            vision: Boolean = false,
            contextTokens: Int = 4096,
        ): Model = Model(File(path).name, null, path, File(path).length(), null, vision, contextTokens)
    }
}
