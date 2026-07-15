package com.jeeves.desktop.data

import com.jeeves.shared.domain.Reminder
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * File-based persistence for Reminders.
 * All reminders stored in ~/Jeeves/reminders.json
 */
class FileReminderRepository {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val file: File
        get() {
            val dir = File(System.getProperty("user.home"), "Jeeves")
            dir.mkdirs()
            return File(dir, "reminders.json")
        }

    fun getAll(): List<Reminder> {
        return try {
            if (file.exists()) {
                json.decodeFromString(ListSerializer(Reminder.serializer()), file.readText())
            } else emptyList()
        } catch (_: Exception) { emptyList() }
    }

    fun save(reminder: Reminder) {
        val all = getAll().toMutableList()
        val i = all.indexOfFirst { it.id == reminder.id }
        if (i >= 0) all[i] = reminder else all.add(reminder)
        write(all)
    }

    fun delete(id: String) {
        write(getAll().filter { it.id != id })
    }

    private fun write(reminders: List<Reminder>) {
        file.writeText(json.encodeToString(ListSerializer(Reminder.serializer()), reminders))
    }
}
