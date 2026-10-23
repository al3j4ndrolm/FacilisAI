package io.github.al3j4ndrolm.facilis.ai

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.startup.Initializer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference
import kotlin.coroutines.resume

/** Keeps the app context and the screen on top, so FacilisAI needs no `Context`. */
internal object Tracker : Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {

    private var appContextOrNull: Context? = null
    private var resumedRef: WeakReference<Activity>? = null

    val appContext: Context
        get() = appContextOrNull ?: error(
            "FacilisAI isn't initialized. Make sure androidx.startup's InitializationProvider " +
                "isn't removed from your merged manifest."
        )

    val resumed: Activity? get() = resumedRef?.get()?.takeUnless { it.isFinishing || it.isDestroyed }

    fun install(context: Context) {
        if (appContextOrNull != null) return
        val app = context.applicationContext as Application
        appContextOrNull = app
        app.registerActivityLifecycleCallbacks(this)
        app.registerComponentCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        resumedRef = WeakReference(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (resumedRef?.get() === activity) resumedRef = null
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    // A loaded model holds a lot of memory: give it back when Android is running low.
    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) {
            Runtime.unloadWhenIdle()
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) = Unit

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = Runtime.unloadWhenIdle()
}

/** Asks the user before a tool marked `confirm = true` runs. */
internal object Confirm {

    suspend fun ask(tool: Tool<*>, arguments: Map<String, Any?>): Boolean {
        FacilisAI.onConfirm?.let { return it(tool, arguments) }
        return withContext(Dispatchers.Main) {
            val activity = Tracker.resumed ?: return@withContext false
            suspendCancellableCoroutine { continuation ->
                val details = arguments.entries.joinToString("\n") { (key, value) -> "$key: $value" }
                val dialog = AlertDialog.Builder(activity)
                    .setTitle(tool.description)
                    .setMessage(details.ifEmpty { null })
                    .setPositiveButton(android.R.string.ok) { _, _ -> if (continuation.isActive) continuation.resume(true) }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> if (continuation.isActive) continuation.resume(false) }
                    .setOnCancelListener { if (continuation.isActive) continuation.resume(false) }
                    .show()
                continuation.invokeOnCancellation { dialog.dismiss() }
            }
        }
    }
}

internal class FacilisAIInitializer : Initializer<Unit> {
    override fun create(context: Context) = Tracker.install(context)

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}
