package com.jeeves.desktop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class SpeakerNameMap(val names: Map<String, String> = emptyMap())

/**
 * Persists speaker label → human name mappings.
 * Stored in ~/Jeeves/speaker-names.json
 */
class SpeakerNameService {
    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }
    private val file = File(System.getProperty("user.home"), "Jeeves/speaker-names.json")
    private var cache: MutableMap<String, String> = mutableMapOf()

    init { load() }

    fun getName(speakerLabel: String): String = cache[speakerLabel] ?: speakerLabel
    fun setName(speakerLabel: String, name: String) { cache[speakerLabel] = name; save() }
    fun getAllNames(): Map<String, String> = cache.toMap()

    private fun load() {
        try {
            if (file.exists()) {
                cache = json.decodeFromString<SpeakerNameMap>(file.readText()).names.toMutableMap()
            }
        } catch (_: Exception) {}
    }

    private fun save() {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(SpeakerNameMap.serializer(), SpeakerNameMap(cache)))
    }
}
