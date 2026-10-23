package io.github.al3j4ndrolm.facilis.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Turns a prompt and an optional picture into what the model reads. */
internal object Images {

    /** Pictures are shrunk to this many pixels on the longest side; models don't see more detail. */
    private const val MAX_SIZE = 1024

    suspend fun contents(text: String, image: Uri?): Contents {
        require(text.isNotBlank() || image != null) { "The prompt is empty" }
        if (image == null) return Contents.of(text)
        val model = FacilisAI.model
        require(model.vision) {
            "${model.name} doesn't understand images. Use a model with vision, e.g. ai.model = Model.Gemma4"
        }
        return Contents.of(Content.ImageBytes(jpeg(image)), Content.Text(text))
    }

    private suspend fun jpeg(uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val resolver = Tracker.appContext.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        // Decoding only the bounds always returns null, so check the stream itself.
        val stream = resolver.openInputStream(uri) ?: throw IOException("Can't open image $uri")
        stream.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) throw IOException("Not an image: $uri")
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE) sample *= 2
        val decoded = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: throw IOException("Can't read image $uri")

        val rotation = resolver.openInputStream(uri)?.use { ExifInterface(it).rotationDegrees } ?: 0
        val scale = MAX_SIZE.toFloat() / maxOf(decoded.width, decoded.height)
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val upright = if (matrix.isIdentity) decoded else
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        ByteArrayOutputStream().use { out ->
            upright.compress(Bitmap.CompressFormat.JPEG, 90, out)
            out.toByteArray()
        }
    }
}
