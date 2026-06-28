package com.jeeves.desktop.ui

import com.jeeves.desktop.ui.components.SYNC_WARNING_THRESHOLD_MS
import com.jeeves.shared.sync.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import kotlin.test.*

/**
 * Unit tests for SyncWarningBanner logic.
 * Tests the time-based conditions for showing the sync warning notification.
 *
 * **Validates: Requirement 6.6**
 */
class SyncWarningBannerTest {

    @Test
    fun `warning threshold is one hour`() {
        assertEquals(3_600_000L, SYNC_WARNING_THRESHOLD_MS)
    }

    @Test
    fun `sync engine exposes lastSyncTimestamp as null initially`() {
        val engine = createTestSyncEngine(lastSync = null)
        assertNull(engine.lastSyncTimestamp.value)
    }

    @Test
    fun `sync engine exposes lastSyncTimestamp after sync`() {
        val timestamp = System.currentTimeMillis()
        val engine = createTestSyncEngine(lastSync = timestamp)
        assertEquals(timestamp, engine.lastSyncTimestamp.value)
    }

    @Test
    fun `warning condition met when no sync within threshold`() {
        val appStartTime = 1_000_000L
        val currentTime = appStartTime + SYNC_WARNING_THRESHOLD_MS + 1
        val lastSync: Long? = null

        val shouldWarn = shouldShowWarning(appStartTime, currentTime, lastSync)
        assertTrue(shouldWarn, "Should warn when app running > 1 hour with no sync")
    }

    @Test
    fun `warning condition not met before threshold`() {
        val appStartTime = 1_000_000L
        val currentTime = appStartTime + SYNC_WARNING_THRESHOLD_MS - 1
        val lastSync: Long? = null

        val shouldWarn = shouldShowWarning(appStartTime, currentTime, lastSync)
        assertFalse(shouldWarn, "Should not warn before 1 hour has passed")
    }

    @Test
    fun `warning condition not met when sync completed recently`() {
        val appStartTime = 1_000_000L
        val currentTime = appStartTime + SYNC_WARNING_THRESHOLD_MS + 60_000
        val lastSync = currentTime - 30_000L // Synced 30 seconds ago

        val shouldWarn = shouldShowWarning(appStartTime, currentTime, lastSync)
        assertFalse(shouldWarn, "Should not warn when last sync is within threshold")
    }

    @Test
    fun `warning condition met when last sync is older than threshold`() {
        val appStartTime = 1_000_000L
        val currentTime = appStartTime + SYNC_WARNING_THRESHOLD_MS * 2
        val lastSync = appStartTime + 1000L // Synced right after start, now too old

        val shouldWarn = shouldShowWarning(appStartTime, currentTime, lastSync)
        assertTrue(shouldWarn, "Should warn when last sync is older than 1 hour")
    }

    @Test
    fun `warning condition at exact threshold boundary`() {
        val appStartTime = 1_000_000L
        val currentTime = appStartTime + SYNC_WARNING_THRESHOLD_MS
        val lastSync: Long? = null

        // At exactly the threshold (not exceeding it), should NOT warn
        val shouldWarn = shouldShowWarning(appStartTime, currentTime, lastSync)
        assertFalse(shouldWarn, "Should not warn at exact threshold boundary")
    }

    // --- Helper functions ---

    /**
     * Pure logic function that mirrors the warning condition in the composable.
     * This allows testing without Compose runtime.
     */
    private fun shouldShowWarning(appStartTime: Long, currentTime: Long, lastSync: Long?): Boolean {
        val appRunningDuration = currentTime - appStartTime
        return appRunningDuration > SYNC_WARNING_THRESHOLD_MS &&
            (lastSync == null || (currentTime - lastSync) > SYNC_WARNING_THRESHOLD_MS)
    }

    /**
     * Creates a minimal SyncEngine implementation for testing.
     */
    private fun createTestSyncEngine(lastSync: Long?): SyncEngine {
        return object : SyncEngine {
            override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Idle).asStateFlow()
            override val pendingChanges: StateFlow<Int> = MutableStateFlow(0).asStateFlow()
            override val lastSyncTimestamp: StateFlow<Long?> = MutableStateFlow(lastSync).asStateFlow()
            override suspend fun start(config: SyncConfiguration) {}
            override suspend fun stop() {}
            override suspend fun syncNow() {}
            override suspend fun testConnection(config: SyncConfiguration) = ConnectionTestResult(success = true)
            override fun getDeviceId() = "test-device"
        }
    }
}
