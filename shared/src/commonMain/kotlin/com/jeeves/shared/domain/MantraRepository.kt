package com.jeeves.shared.domain

/**
 * Persistence interface for Mantras and their execution history.
 */
interface MantraRepository {
    suspend fun getMantras(): List<Mantra>
    suspend fun getMantra(id: String): Mantra?
    suspend fun saveMantra(mantra: Mantra)
    suspend fun deleteMantra(id: String)

    suspend fun getExecutionsForDate(date: String): List<MantraExecution>
    suspend fun getExecution(id: String): MantraExecution?
    suspend fun saveExecution(execution: MantraExecution)
    suspend fun deleteExecution(id: String)
}
