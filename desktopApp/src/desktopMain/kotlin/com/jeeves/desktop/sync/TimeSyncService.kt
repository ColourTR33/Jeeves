package com.jeeves.desktop.sync

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.domain.Project
import com.jeeves.shared.domain.TimeEntry
import com.jeeves.shared.domain.TimeTrackingRepository
import com.jeeves.shared.time.TimeTrackingManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/**
 * Lightweight CouchDB sync for time entries and projects.
 *
 * Strategy:
 * - Push: local entries → CouchDB (upsert by _id)
 * - Pull: CouchDB entries (from PWA) → local storage
 * - Projects are pushed to CouchDB so the PWA can read them
 * - Conflict resolution: latest updatedAt wins
 * - Runs on a configurable interval (default 60s)
 */
class TimeSyncService(
    private val repository: TimeTrackingRepository,
    private val scope: CoroutineScope
) {
    @Serializable
    enum class SyncStatus { IDLE, SYNCING, SUCCESS, ERROR }

    private val _status = MutableStateFlow(SyncStatus.IDLE)
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _lastSyncTime = MutableStateFlow(0L)
    val lastSyncTime: StateFlow<Long> = _lastSyncTime.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var syncJob: Job? = null
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // Configuration (set before calling start())
    var couchDbUrl: String = ""         // e.g. "https://notes.thehartleys.uk/jeeves-time"
    var username: String = ""
    var password: String = ""
    var intervalSeconds: Int = 60
    var deviceId: String = "desktop"

    /**
     * Start periodic sync. Call after configuring URL/credentials.
     */
    fun start() {
        if (couchDbUrl.isBlank()) {
            AppLogger.info("TimeSyncService", "Sync not configured (no URL)")
            return
        }
        stop()
        syncJob = scope.launch {
            AppLogger.info("TimeSyncService", "Sync started: $couchDbUrl (every ${intervalSeconds}s)")
            while (isActive) {
                syncOnce()
                delay(intervalSeconds * 1000L)
            }
        }
    }

    fun stop() {
        syncJob?.cancel()
        syncJob = null
    }

    /**
     * Trigger a single sync cycle manually.
     */
    suspend fun syncOnce() {
        if (couchDbUrl.isBlank()) return
        _status.value = SyncStatus.SYNCING
        try {
            pushProjects()
            pushTimeEntries()
            deleteRemovedEntries()
            pullTimeEntries()
            _status.value = SyncStatus.SUCCESS
            _lastSyncTime.value = System.currentTimeMillis()
            _lastError.value = null
            AppLogger.debug("TimeSyncService", "Sync cycle complete")
        } catch (e: Exception) {
            _status.value = SyncStatus.ERROR
            _lastError.value = e.message
            AppLogger.warn("TimeSyncService", "Sync failed: ${e.message}")
        }
    }

    // ─── Push Projects ──────────────────────────────────────────────────────────

    private suspend fun pushProjects() {
        val projects = repository.getProjects()
        for (project in projects) {
            val docId = "project_${project.id}"
            val doc = buildJsonObject {
                put("_id", docId)
                put("type", "project")
                put("projectId", project.id)
                put("name", project.name)
                put("isBillable", project.isBillable)
                put("isDistributed", project.isDistributed)
                put("color", project.color)
                put("defaultTargetHours", project.defaultTargetHours)
                put("tdmName", project.tdmName)
                put("contactName", project.contactName)
                put("companyName", project.companyName)
                put("updatedAt", System.currentTimeMillis())
            }
            upsertDoc(docId, doc)
        }
    }

    // ─── Push Time Entries ──────────────────────────────────────────────────────

    private suspend fun pushTimeEntries() {
        // Push entries from the last 14 days
        val today = TimeTrackingManager.epochToDateString(System.currentTimeMillis())
        val twoWeeksAgo = TimeTrackingManager.epochToDateString(System.currentTimeMillis() - 14 * 86_400_000L)
        val entries = repository.getTimeEntries(twoWeeksAgo, today)

        for (entry in entries) {
            val docId = "entry_${entry.id}"
            val doc = buildJsonObject {
                put("_id", docId)
                put("type", "time_entry")
                put("entryId", entry.id)
                put("projectId", entry.projectId)
                put("taskDescription", entry.taskDescription)
                put("startTime", entry.startTime)
                entry.endTime?.let { put("endTime", it) }
                entry.durationMs?.let { put("durationMs", it) }
                put("date", entry.date)
                put("isRunning", entry.isRunning)
                put("deviceId", deviceId)
                entry.linkedRecordingId?.let { put("linkedRecordingId", it) }
                put("updatedAt", System.currentTimeMillis())
            }
            upsertDoc(docId, doc)
        }
    }

    // ─── Delete Removed Entries (sync deletions to CouchDB) ─────────────────────

    /**
     * Compare local entries with what this device has pushed to CouchDB.
     * Any CouchDB docs from this device that no longer exist locally get deleted.
     */
    private suspend fun deleteRemovedEntries() {
        val today = TimeTrackingManager.epochToDateString(System.currentTimeMillis())
        val twoWeeksAgo = TimeTrackingManager.epochToDateString(System.currentTimeMillis() - 14 * 86_400_000L)
        val localEntries = repository.getTimeEntries(twoWeeksAgo, today)
        val localIds = localEntries.map { "entry_${it.id}" }.toSet()

        // Get all entry docs from CouchDB
        val viewUrl = "$couchDbUrl/_all_docs?include_docs=true"
        val responseBody = httpGet(viewUrl) ?: return

        try {
            val response = json.parseToJsonElement(responseBody).jsonObject
            val rows = response["rows"]?.jsonArray ?: return

            for (row in rows) {
                val doc = row.jsonObject["doc"]?.jsonObject ?: continue
                val type = doc["type"]?.jsonPrimitive?.contentOrNull ?: continue
                if (type != "time_entry") continue

                val docDeviceId = doc["deviceId"]?.jsonPrimitive?.contentOrNull ?: ""
                if (docDeviceId != deviceId) continue // Only delete our own entries

                val docId = doc["_id"]?.jsonPrimitive?.contentOrNull ?: continue
                val rev = doc["_rev"]?.jsonPrimitive?.contentOrNull ?: continue

                // If this doc doesn't exist locally anymore, delete it from CouchDB
                if (docId !in localIds) {
                    val deleteUrl = "$couchDbUrl/$docId?rev=$rev"
                    httpDelete(deleteUrl)
                    AppLogger.info("TimeSyncService", "Deleted remote entry: $docId (no longer exists locally)")
                }
            }
        } catch (e: Exception) {
            AppLogger.warn("TimeSyncService", "Failed to sync deletions: ${e.message}")
        }
    }

    // ─── Pull Time Entries (from PWA) ───────────────────────────────────────────

    private suspend fun pullTimeEntries() {
        // Query all time_entry docs where deviceId != this device
        val viewUrl = "$couchDbUrl/_all_docs?include_docs=true"
        val responseBody = httpGet(viewUrl) ?: return

        try {
            val response = json.parseToJsonElement(responseBody).jsonObject
            val rows = response["rows"]?.jsonArray ?: return

            for (row in rows) {
                val doc = row.jsonObject["doc"]?.jsonObject ?: continue
                val type = doc["type"]?.jsonPrimitive?.contentOrNull ?: continue
                if (type != "time_entry") continue

                val docDeviceId = doc["deviceId"]?.jsonPrimitive?.contentOrNull ?: ""
                if (docDeviceId == deviceId) continue // Skip our own entries

                // This is a remote entry (from PWA) — import it locally
                val entryId = doc["entryId"]?.jsonPrimitive?.contentOrNull ?: continue
                val projectId = doc["projectId"]?.jsonPrimitive?.contentOrNull ?: continue
                val date = doc["date"]?.jsonPrimitive?.contentOrNull ?: continue
                val isRunning = doc["isRunning"]?.jsonPrimitive?.booleanOrNull ?: false

                // Skip running entries from other devices (they'll finalize themselves)
                if (isRunning) continue

                val startTime = doc["startTime"]?.jsonPrimitive?.longOrNull ?: continue
                val endTime = doc["endTime"]?.jsonPrimitive?.longOrNull
                val durationMs = doc["durationMs"]?.jsonPrimitive?.longOrNull
                val taskDescription = doc["taskDescription"]?.jsonPrimitive?.contentOrNull ?: ""

                // Check if we already have this entry locally
                val existingEntries = repository.getTimeEntriesForDate(date)
                if (existingEntries.any { it.id == entryId }) continue

                // Import it
                val entry = TimeEntry(
                    id = entryId,
                    projectId = projectId,
                    taskDescription = taskDescription,
                    startTime = startTime,
                    endTime = endTime,
                    durationMs = durationMs,
                    date = date,
                    isRunning = false
                )
                repository.saveTimeEntry(entry)
                AppLogger.info("TimeSyncService", "Pulled remote entry: $taskDescription ($date) from device $docDeviceId")
            }
        } catch (e: Exception) {
            AppLogger.warn("TimeSyncService", "Failed parsing pull response: ${e.message}")
        }
    }

    // ─── CouchDB HTTP Helpers ───────────────────────────────────────────────────

    /**
     * Upsert a document: fetch current _rev, then PUT with it.
     */
    private fun upsertDoc(docId: String, doc: JsonObject) {
        val url = "$couchDbUrl/$docId"

        // Get current revision (if exists)
        val existing = httpGet(url)
        val rev = if (existing != null) {
            try {
                json.parseToJsonElement(existing).jsonObject["_rev"]?.jsonPrimitive?.contentOrNull
            } catch (_: Exception) { null }
        } else null

        // Build doc with _rev if updating
        val finalDoc = if (rev != null) {
            buildJsonObject {
                doc.forEach { (k, v) -> put(k, v) }
                put("_rev", rev)
            }
        } else doc

        httpPut(url, finalDoc.toString())
    }

    private fun authHeader(): String {
        val creds = Base64.getEncoder().encodeToString("$username:$password".toByteArray())
        return "Basic $creds"
    }

    private fun httpGet(url: String): String? {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", authHeader())
            conn.setRequestProperty("Accept", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000

            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().readText()
            } else null
        } catch (_: Exception) { null }
    }

    private fun httpPut(url: String, body: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Authorization", authHeader())
            conn.setRequestProperty("Content-Type", "application/json")
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            conn.doOutput = true
            conn.outputStream.bufferedWriter().use { it.write(body) }

            conn.responseCode in 200..299
        } catch (_: Exception) { false }
    }

    private fun httpDelete(url: String): Boolean {
        return try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.setRequestProperty("Authorization", authHeader())
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000

            conn.responseCode in 200..299
        } catch (_: Exception) { false }
    }
}
