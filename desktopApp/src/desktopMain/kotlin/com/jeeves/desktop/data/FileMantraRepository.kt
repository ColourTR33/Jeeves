package com.jeeves.desktop.data

import com.jeeves.shared.domain.Mantra
import com.jeeves.shared.domain.MantraExecution
import com.jeeves.shared.domain.MantraRepository
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * File-based persistence for Mantras and execution history.
 * Stores mantras in ~/Jeeves/mantras/mantras.json
 * Stores executions in ~/Jeeves/mantras/executions/YYYY-MM-DD.json (one file per day)
 */
class FileMantraRepository : MantraRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val baseDir: File
        get() {
            val dir = File(System.getProperty("user.home"), "Jeeves/mantras")
            dir.mkdirs()
            return dir
        }

    private val mantrasFile: File get() = File(baseDir, "mantras.json")

    private val executionsDir: File
        get() {
            val dir = File(baseDir, "executions")
            dir.mkdirs()
            return dir
        }

    // ─── Mantras ────────────────────────────────────────────────────────────────

    override suspend fun getMantras(): List<Mantra> {
        return try {
            if (mantrasFile.exists()) {
                json.decodeFromString(ListSerializer(Mantra.serializer()), mantrasFile.readText())
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getMantra(id: String): Mantra? {
        return getMantras().find { it.id == id }
    }

    override suspend fun saveMantra(mantra: Mantra) {
        val all = getMantras().toMutableList()
        val index = all.indexOfFirst { it.id == mantra.id }
        if (index >= 0) all[index] = mantra else all.add(mantra)
        mantrasFile.writeText(json.encodeToString(ListSerializer(Mantra.serializer()), all))
    }

    override suspend fun deleteMantra(id: String) {
        val all = getMantras().filter { it.id != id }
        mantrasFile.writeText(json.encodeToString(ListSerializer(Mantra.serializer()), all))
    }

    // ─── Executions ─────────────────────────────────────────────────────────────

    private fun executionFile(date: String): File = File(executionsDir, "$date.json")

    override suspend fun getExecutionsForDate(date: String): List<MantraExecution> {
        val file = executionFile(date)
        return try {
            if (file.exists()) {
                json.decodeFromString(ListSerializer(MantraExecution.serializer()), file.readText())
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun getExecution(id: String): MantraExecution? {
        // Search today's file first, then recent days
        val today = com.jeeves.shared.time.TimeTrackingManager.epochToDateString(System.currentTimeMillis())
        return getExecutionsForDate(today).find { it.id == id }
    }

    override suspend fun saveExecution(execution: MantraExecution) {
        val all = getExecutionsForDate(execution.date).toMutableList()
        val index = all.indexOfFirst { it.id == execution.id }
        if (index >= 0) all[index] = execution else all.add(execution)
        executionFile(execution.date).writeText(
            json.encodeToString(ListSerializer(MantraExecution.serializer()), all)
        )
    }

    override suspend fun deleteExecution(id: String) {
        val today = com.jeeves.shared.time.TimeTrackingManager.epochToDateString(System.currentTimeMillis())
        val all = getExecutionsForDate(today).filter { it.id != id }
        executionFile(today).writeText(
            json.encodeToString(ListSerializer(MantraExecution.serializer()), all)
        )
    }
}
