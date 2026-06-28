package com.jeeves.shared.sync

import kotlinx.coroutines.test.runTest
import kotlin.test.*
import java.io.File

/**
 * Unit tests for ConflictAuditLog persistence and 90-day retention.
 *
 * **Validates: Requirements 5.6, 5.7**
 */
class ConflictAuditLogTest {

    private lateinit var tempDir: File

    @BeforeTest
    fun setup() {
        tempDir = createTempDirectory()
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // Basic append and retrieval
    // =========================================================================

    @Test
    fun append_storesEntryAndRetrievable() = runTest {
        val log = ConflictAuditLog(tempDir)
        val entry = makeEntry(resolvedAt = System.currentTimeMillis())

        log.append(entry)

        val all = log.getAll()
        assertEquals(1, all.size)
        assertEquals(entry, all[0])
    }

    @Test
    fun append_multipleEntries_allRetrievable() = runTest {
        val log = ConflictAuditLog(tempDir)
        val now = System.currentTimeMillis()
        val entry1 = makeEntry(id = "entry-1", resolvedAt = now - 1000)
        val entry2 = makeEntry(id = "entry-2", resolvedAt = now)

        log.append(entry1)
        log.append(entry2)

        val all = log.getAll()
        assertEquals(2, all.size)
        assertTrue(all.contains(entry1))
        assertTrue(all.contains(entry2))
    }

    @Test
    fun getAll_emptyLog_returnsEmptyList() = runTest {
        val log = ConflictAuditLog(tempDir)

        val all = log.getAll()
        assertTrue(all.isEmpty())
    }

    // =========================================================================
    // Persistence across restarts
    // =========================================================================

    @Test
    fun entries_persistAcrossInstances() = runTest {
        val entry = makeEntry(resolvedAt = System.currentTimeMillis())

        // First instance: write entry
        val log1 = ConflictAuditLog(tempDir)
        log1.append(entry)

        // Second instance: should load persisted entry
        val log2 = ConflictAuditLog(tempDir)
        val all = log2.getAll()
        assertEquals(1, all.size)
        assertEquals(entry, all[0])
    }

    // =========================================================================
    // 90-day retention / pruning
    // =========================================================================

    @Test
    fun pruneExpired_removesEntriesOlderThan90Days() = runTest {
        val now = System.currentTimeMillis()
        val oldEntry = makeEntry(
            id = "old-entry",
            resolvedAt = now - ConflictAuditLog.RETENTION_PERIOD_MS - 1000 // 90 days + 1 second ago
        )
        val recentEntry = makeEntry(
            id = "recent-entry",
            resolvedAt = now - 1000 // 1 second ago
        )

        val log = ConflictAuditLog(tempDir)
        log.append(oldEntry)
        log.append(recentEntry)

        log.pruneExpired()

        val all = log.getAll()
        assertEquals(1, all.size)
        assertEquals("recent-entry", all[0].id)
    }

    @Test
    fun pruneExpired_keepsEntriesExactlyAt90Days() = runTest {
        val now = System.currentTimeMillis()
        // Entry at exactly 90 days minus 1ms (still within retention)
        val borderlineEntry = makeEntry(
            id = "borderline",
            resolvedAt = now - ConflictAuditLog.RETENTION_PERIOD_MS + 1000
        )

        val log = ConflictAuditLog(tempDir)
        log.append(borderlineEntry)

        log.pruneExpired()

        val all = log.getAll()
        assertEquals(1, all.size)
        assertEquals("borderline", all[0].id)
    }

    @Test
    fun initialization_prunesExpiredEntries() = runTest {
        val now = System.currentTimeMillis()
        val oldEntry = makeEntry(
            id = "expired",
            resolvedAt = now - ConflictAuditLog.RETENTION_PERIOD_MS - 60_000 // 90 days + 1 minute
        )
        val freshEntry = makeEntry(
            id = "fresh",
            resolvedAt = now
        )

        // First instance: write both entries
        val log1 = ConflictAuditLog(tempDir)
        log1.append(oldEntry)
        log1.append(freshEntry)

        // Second instance: should prune expired entry on startup
        val log2 = ConflictAuditLog(tempDir)
        val all = log2.getAll()
        assertEquals(1, all.size)
        assertEquals("fresh", all[0].id)
    }

    @Test
    fun pruneExpired_allExpired_resultsInEmptyLog() = runTest {
        val now = System.currentTimeMillis()
        val expiredEntry = makeEntry(
            id = "old",
            resolvedAt = now - ConflictAuditLog.RETENTION_PERIOD_MS - 1
        )

        val log = ConflictAuditLog(tempDir)
        log.append(expiredEntry)

        log.pruneExpired()

        val all = log.getAll()
        assertTrue(all.isEmpty())
    }

    @Test
    fun pruneExpired_noExpired_noChanges() = runTest {
        val now = System.currentTimeMillis()
        val entry = makeEntry(resolvedAt = now)

        val log = ConflictAuditLog(tempDir)
        log.append(entry)

        log.pruneExpired()

        val all = log.getAll()
        assertEquals(1, all.size)
    }

    // =========================================================================
    // File handling edge cases
    // =========================================================================

    @Test
    fun corruptedFile_startsWithEmptyLog() = runTest {
        // Write corrupted data to the audit log file
        val auditFile = File(tempDir, "_audit_log.json")
        tempDir.mkdirs()
        auditFile.writeText("not valid json {{{")

        val log = ConflictAuditLog(tempDir)
        val all = log.getAll()
        assertTrue(all.isEmpty())
    }

    @Test
    fun directoryCreatedIfNotExists() = runTest {
        val nestedDir = File(tempDir, "nested/audit")
        assertFalse(nestedDir.exists())

        val log = ConflictAuditLog(nestedDir)
        log.append(makeEntry(resolvedAt = System.currentTimeMillis()))

        assertTrue(nestedDir.exists())
        val all = log.getAll()
        assertEquals(1, all.size)
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private fun makeEntry(
        id: String = "audit-001",
        documentId: String = "recording:rec-123",
        localDeviceId: String = "device-A",
        remoteDeviceId: String = "device-B",
        strategy: ResolutionStrategy = ResolutionStrategy.LAST_WRITE_WINS,
        resolvedAt: Long = System.currentTimeMillis(),
        localRev: String = "1-abc",
        remoteRev: String = "1-def",
        winnerRev: String = "1-def"
    ): ConflictAuditEntry {
        return ConflictAuditEntry(
            id = id,
            documentId = documentId,
            localDeviceId = localDeviceId,
            remoteDeviceId = remoteDeviceId,
            strategy = strategy,
            resolvedAt = resolvedAt,
            localRev = localRev,
            remoteRev = remoteRev,
            winnerRev = winnerRev
        )
    }

    private fun createTempDirectory(): File {
        val dir = File(System.getProperty("java.io.tmpdir"), "conflict-audit-log-test-${System.nanoTime()}")
        dir.mkdirs()
        return dir
    }
}
