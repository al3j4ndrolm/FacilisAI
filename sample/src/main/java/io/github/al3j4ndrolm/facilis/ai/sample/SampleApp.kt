package io.github.al3j4ndrolm.facilis.ai.sample

import android.app.Application
import io.github.al3j4ndrolm.facilis.ai.Describe
import io.github.al3j4ndrolm.facilis.ai.ai
import io.github.al3j4ndrolm.facilis.notification.notify
import io.github.al3j4ndrolm.facilis.storage.storage
import kotlinx.serialization.Serializable
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.time.Duration.Companion.minutes

/** Notes the agent saves, kept with FacilisStorage. */
val notes get() = storage.list<String>("notes")

@Serializable
data class Reminder(
    @Describe("What to remind the user about") val title: String,
    @Describe("Minutes from now") val minutes: Int,
)

@Serializable
data class Note(@Describe("The note text") val text: String)

class SampleApp : Application() {
    override fun onCreate() {
        super.onCreate()

        // The rest of the Facilis family, as things the model can do.
        ai.tool<Reminder>("set_reminder", "Show the user a reminder notification in some minutes") { r ->
            notify.after(r.minutes.minutes, r.title)
            "Reminder \"${r.title}\" set for ${r.minutes} minutes from now"
        }
        ai.tool<Note>("save_note", "Save a note for the user") { note ->
            notes.add(note.text)
            "Saved"
        }
        ai.tool("read_notes", "Read the user's saved notes") {
            notes.all().ifEmpty { listOf("(no notes)") }
        }
        ai.tool("get_time", "Get the current date and time") {
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("EEEE, MMMM d, h:mm a"))
        }
        ai.tool("delete_notes", "Delete all of the user's notes", confirm = true) {
            notes.clear()
            "Deleted"
        }
    }
}
