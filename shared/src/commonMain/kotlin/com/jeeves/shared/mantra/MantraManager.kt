package com.jeeves.shared.mantra

import com.jeeves.shared.ai.AppLogger
import com.jeeves.shared.domain.*
import com.jeeves.shared.recording.currentTimeMillis
import com.jeeves.shared.recording.generateId
import com.jeeves.shared.time.TimeTrackingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages Mantra CRUD, execution lifecycle, step progression, and time logging.
 *
 * Key responsibilities:
 * - Load/save mantras
 * - Start/stop/skip executions
 * - Track current step and countdown
 * - Log completed executions to the time tracker
 */
class MantraManager(
    private val repository: MantraRepository,
    private val timeManager: TimeTrackingManager,
    private val scope: CoroutineScope
) {
    private val _mantras = MutableStateFlow<List<Mantra>>(emptyList())
    val mantras: StateFlow<List<Mantra>> = _mantras.asStateFlow()

    private val _activeExecution = MutableStateFlow<MantraExecution?>(null)
    val activeExecution: StateFlow<MantraExecution?> = _activeExecution.asStateFlow()

    /** The mantra definition for the currently active execution. */
    private val _activeMantra = MutableStateFlow<Mantra?>(null)
    val activeMantra: StateFlow<Mantra?> = _activeMantra.asStateFlow()

    /** Seconds remaining on the current step's countdown. Updated by UI timer. */
    private val _stepSecondsRemaining = MutableStateFlow(0)
    val stepSecondsRemaining: StateFlow<Int> = _stepSecondsRemaining.asStateFlow()

    /** Whether the countdown is actively ticking (false = paused). */
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    /** Today's completed executions for display. */
    private val _todayExecutions = MutableStateFlow<List<MantraExecution>>(emptyList())
    val todayExecutions: StateFlow<List<MantraExecution>> = _todayExecutions.asStateFlow()

    fun initialize() {
        scope.launch {
            _mantras.value = repository.getMantras()
            refreshTodayExecutions()
        }
    }

    private suspend fun refreshTodayExecutions() {
        val today = TimeTrackingManager.epochToDateString(currentTimeMillis())
        _todayExecutions.value = repository.getExecutionsForDate(today)
    }

    // ─── CRUD ───────────────────────────────────────────────────────────────────

    fun saveMantra(mantra: Mantra) {
        scope.launch {
            val updated = mantra.copy(updatedAt = currentTimeMillis())
            repository.saveMantra(updated)
            _mantras.value = repository.getMantras()
            AppLogger.info("MantraManager", "Saved mantra: ${updated.name}")
        }
    }

    fun deleteMantra(id: String) {
        scope.launch {
            repository.deleteMantra(id)
            _mantras.value = repository.getMantras()
        }
    }

    fun toggleActive(id: String) {
        scope.launch {
            val mantra = repository.getMantra(id) ?: return@launch
            repository.saveMantra(mantra.copy(isActive = !mantra.isActive, updatedAt = currentTimeMillis()))
            _mantras.value = repository.getMantras()
        }
    }

    // ─── Execution Lifecycle ────────────────────────────────────────────────────

    /**
     * Start a new execution of a mantra. Sets up the first step countdown.
     */
    fun startExecution(mantraId: String) {
        scope.launch {
            val mantra = repository.getMantra(mantraId) ?: return@launch
            if (mantra.steps.isEmpty()) return@launch

            // Abort any existing active execution
            val current = _activeExecution.value
            if (current != null && current.status == MantraExecutionStatus.IN_PROGRESS) {
                skipExecution()
            }

            val execution = MantraExecution(
                id = generateId(),
                mantraId = mantraId,
                date = TimeTrackingManager.epochToDateString(currentTimeMillis()),
                startedAt = currentTimeMillis(),
                currentStepIndex = 0,
                completedSteps = emptyList(),
                status = MantraExecutionStatus.IN_PROGRESS
            )

            repository.saveExecution(execution)
            _activeExecution.value = execution
            _activeMantra.value = mantra
            _stepSecondsRemaining.value = mantra.steps[0].durationSeconds
            _isRunning.value = true

            AppLogger.info("MantraManager", "Started mantra: ${mantra.name}")
        }
    }

    /**
     * Called by the UI every second while isRunning is true.
     * Decrements the countdown. When it hits zero, the step is auto-completed.
     */
    fun tick() {
        val remaining = _stepSecondsRemaining.value
        if (!_isRunning.value || remaining <= 0) return

        _stepSecondsRemaining.value = remaining - 1

        // Auto-advance when timer hits zero (step is done)
        if (_stepSecondsRemaining.value <= 0) {
            _isRunning.value = false // Pause — user must check off to advance
        }
    }

    /**
     * User checks off the current step and moves to the next one.
     * If this was the last step, completes the execution.
     */
    fun checkOffCurrentStep() {
        scope.launch {
            val execution = _activeExecution.value ?: return@launch
            val mantra = _activeMantra.value ?: return@launch
            val currentStep = mantra.steps.getOrNull(execution.currentStepIndex) ?: return@launch

            val updatedCompleted = execution.completedSteps + currentStep.id
            val nextIndex = execution.currentStepIndex + 1

            if (nextIndex >= mantra.steps.size) {
                // All steps done — complete the execution
                val completed = execution.copy(
                    completedSteps = updatedCompleted,
                    currentStepIndex = nextIndex,
                    completedAt = currentTimeMillis(),
                    status = MantraExecutionStatus.COMPLETED,
                    elapsedSeconds = ((currentTimeMillis() - execution.startedAt) / 1000).toInt()
                )
                repository.saveExecution(completed)
                _activeExecution.value = null
                _activeMantra.value = null
                _isRunning.value = false
                _stepSecondsRemaining.value = 0

                // Log time if project is set
                logTimeForExecution(mantra, completed)
                refreshTodayExecutions()
                AppLogger.info("MantraManager", "Completed mantra: ${mantra.name} (${completed.elapsedSeconds}s)")
            } else {
                // Move to next step
                val updated = execution.copy(
                    completedSteps = updatedCompleted,
                    currentStepIndex = nextIndex
                )
                repository.saveExecution(updated)
                _activeExecution.value = updated
                _stepSecondsRemaining.value = mantra.steps[nextIndex].durationSeconds
                _isRunning.value = true
            }
        }
    }

    /**
     * Skip the rest of the current step's timer and mark it ready to check off.
     */
    fun skipStepTimer() {
        _stepSecondsRemaining.value = 0
        _isRunning.value = false
    }

    /**
     * Pause/resume the countdown.
     */
    fun togglePause() {
        if (_stepSecondsRemaining.value > 0) {
            _isRunning.value = !_isRunning.value
        }
    }

    /**
     * Abandon the current execution entirely.
     */
    fun skipExecution() {
        scope.launch {
            val execution = _activeExecution.value ?: return@launch
            val skipped = execution.copy(
                status = MantraExecutionStatus.SKIPPED,
                completedAt = currentTimeMillis(),
                elapsedSeconds = ((currentTimeMillis() - execution.startedAt) / 1000).toInt()
            )
            repository.saveExecution(skipped)
            _activeExecution.value = null
            _activeMantra.value = null
            _isRunning.value = false
            _stepSecondsRemaining.value = 0
            refreshTodayExecutions()
        }
    }

    // ─── Time Logging ───────────────────────────────────────────────────────────

    private fun logTimeForExecution(mantra: Mantra, execution: MantraExecution) {
        if (mantra.projectId.isBlank()) return

        val durationMs = execution.elapsedSeconds * 1000L
        timeManager.addManualEntry(
            projectId = mantra.projectId,
            taskDescription = "Mantra: ${mantra.name}",
            date = execution.date,
            durationMs = durationMs
        )
    }

    // ─── Schedule Helpers ───────────────────────────────────────────────────────

    /**
     * Get mantras scheduled for today that haven't been executed yet.
     */
    fun getDueMantras(): List<Mantra> {
        val today = todayDayOfWeek()
        val todayExecs = _todayExecutions.value
        return _mantras.value.filter { mantra ->
            mantra.isActive &&
            mantra.schedule.daysOfWeek.contains(today) &&
            todayExecs.none { exec ->
                exec.mantraId == mantra.id && exec.status == MantraExecutionStatus.COMPLETED
            }
        }
    }

    private fun todayDayOfWeek(): DayOfWeek {
        val cal = java.util.Calendar.getInstance()
        return when (cal.get(java.util.Calendar.DAY_OF_WEEK)) {
            java.util.Calendar.MONDAY -> DayOfWeek.MONDAY
            java.util.Calendar.TUESDAY -> DayOfWeek.TUESDAY
            java.util.Calendar.WEDNESDAY -> DayOfWeek.WEDNESDAY
            java.util.Calendar.THURSDAY -> DayOfWeek.THURSDAY
            java.util.Calendar.FRIDAY -> DayOfWeek.FRIDAY
            java.util.Calendar.SATURDAY -> DayOfWeek.SATURDAY
            java.util.Calendar.SUNDAY -> DayOfWeek.SUNDAY
            else -> DayOfWeek.MONDAY
        }
    }
}
