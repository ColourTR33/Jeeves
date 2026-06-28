package com.jeeves.shared.sync

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Feature: multi-device-sync, Property 5: Note concatenation preserves all content

/**
 * Property-based tests for note concatenation conflict resolution.
 *
 * For any two conflicting Recording documents with non-empty postRecordingNote fields,
 * the resolved note SHALL contain the full text of both conflicting notes, ordered by
 * modifiedAt ascending, with each prefixed by the originating deviceId.
 *
 * **Validates: Requirements 5.3**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class NoteConcatenationPropertyTest {

    private val config = PropTestConfig(iterations = 100)

    // =========================================================================
    // Generators
    // =========================================================================

    /** Generate non-empty device IDs (no newlines to avoid format ambiguity) */
    private val arbDeviceId: Arb<String> = Arb.string(3..30)
        .filter { it.isNotBlank() && !it.contains('\n') && !it.contains('[') && !it.contains(']') }

    /** Generate non-empty notes (no newlines to keep single-line per device) */
    private val arbNonEmptyNote: Arb<String> = Arb.string(1..200)
        .filter { it.isNotBlank() && !it.contains('\n') }

    /** Generate distinct timestamps */
    private val arbTimestamp: Arb<Long> = Arb.long(1L..9_999_999_999_999L)

    /**
     * Generate a pair of distinct timestamps (older, newer) to ensure clear ordering.
     */
    private val arbDistinctTimestampPair: Arb<Pair<Long, Long>> = Arb.bind(
        Arb.long(1L..4_999_999_999_999L),
        Arb.long(1L..4_999_999_999_999L)
    ) { a, b ->
        if (a == b) {
            a to (a + 1)
        } else {
            minOf(a, b) to maxOf(a, b)
        }
    }

    /**
     * Generate a pair of distinct device IDs.
     */
    private val arbDistinctDeviceIdPair: Arb<Pair<String, String>> = Arb.bind(
        arbDeviceId,
        arbDeviceId
    ) { a, b ->
        if (a == b) {
            a to "${b}X"
        } else {
            a to b
        }
    }

    private fun makeRecordingDoc(
        deviceId: String,
        modifiedAt: Long,
        note: String
    ): CouchDocument {
        val body = buildJsonObject {
            put("id", "rec-test")
            put("filePath", "/audio/test.wav")
            put("durationMs", 60000L)
            put("createdAt", 1000L)
            put("title", "Test Recording")
            put("description", "A test recording")
            put("template", "GENERAL")
            put("tags", JsonArray(emptyList()))
            put("folder", "")
            put("highlights", JsonArray(emptyList()))
            put("attachments", JsonArray(emptyList()))
            put("postRecordingNote", note)
        }
        return CouchDocument(
            id = "recording:rec-test",
            rev = "1-${deviceId.hashCode().toUInt().toString(16)}",
            type = "recording",
            deviceId = deviceId,
            modifiedAt = modifiedAt,
            body = body
        )
    }

    // =========================================================================
    // Property Tests
    // =========================================================================

    /**
     * Property 5: Resolved note contains full text of both conflicting notes.
     *
     * For any two recordings with non-empty notes and distinct timestamps,
     * the resolved note must contain the complete text of both notes.
     *
     * **Validates: Requirements 5.3**
     */
    @Test
    fun property5_resolvedNote_containsBothNotes() = runTest {
        checkAll(config, arbDistinctDeviceIdPair, arbDistinctTimestampPair, arbNonEmptyNote, arbNonEmptyNote) {
            (deviceIdA, deviceIdB), (olderTs, newerTs), noteA, noteB ->

            val resolver = ConflictResolver("test-device")

            val docOlder = makeRecordingDoc(deviceId = deviceIdA, modifiedAt = olderTs, note = noteA)
            val docNewer = makeRecordingDoc(deviceId = deviceIdB, modifiedAt = newerTs, note = noteB)

            val result = resolver.resolveRecording(docOlder, docNewer)
            val resolvedNote = result.body["postRecordingNote"]?.jsonPrimitive?.content ?: ""

            assertTrue(
                resolvedNote.contains(noteA),
                "Resolved note must contain the older note text '$noteA'. Got: '$resolvedNote'"
            )
            assertTrue(
                resolvedNote.contains(noteB),
                "Resolved note must contain the newer note text '$noteB'. Got: '$resolvedNote'"
            )
        }
    }

    /**
     * Property 5: Resolved note is ordered by modifiedAt ascending.
     *
     * The note from the document with the smaller modifiedAt appears before
     * the note from the document with the larger modifiedAt.
     *
     * **Validates: Requirements 5.3**
     */
    @Test
    fun property5_resolvedNote_orderedByTimestampAscending() = runTest {
        checkAll(config, arbDistinctDeviceIdPair, arbDistinctTimestampPair, arbNonEmptyNote, arbNonEmptyNote) {
            (deviceIdA, deviceIdB), (olderTs, newerTs), noteA, noteB ->

            val resolver = ConflictResolver("test-device")

            val docOlder = makeRecordingDoc(deviceId = deviceIdA, modifiedAt = olderTs, note = noteA)
            val docNewer = makeRecordingDoc(deviceId = deviceIdB, modifiedAt = newerTs, note = noteB)

            val result = resolver.resolveRecording(docOlder, docNewer)
            val resolvedNote = result.body["postRecordingNote"]?.jsonPrimitive?.content ?: ""

            val indexOfOlderNote = resolvedNote.indexOf(noteA)
            val indexOfNewerNote = resolvedNote.indexOf(noteB)

            assertTrue(
                indexOfOlderNote < indexOfNewerNote,
                "Older note (modifiedAt=$olderTs) must appear before newer note (modifiedAt=$newerTs). " +
                    "Resolved: '$resolvedNote'"
            )
        }
    }

    /**
     * Property 5: Each note in the resolved text is prefixed by its originating deviceId.
     *
     * Format: "[deviceId]: note"
     *
     * **Validates: Requirements 5.3**
     */
    @Test
    fun property5_resolvedNote_prefixedByDeviceId() = runTest {
        checkAll(config, arbDistinctDeviceIdPair, arbDistinctTimestampPair, arbNonEmptyNote, arbNonEmptyNote) {
            (deviceIdA, deviceIdB), (olderTs, newerTs), noteA, noteB ->

            val resolver = ConflictResolver("test-device")

            val docOlder = makeRecordingDoc(deviceId = deviceIdA, modifiedAt = olderTs, note = noteA)
            val docNewer = makeRecordingDoc(deviceId = deviceIdB, modifiedAt = newerTs, note = noteB)

            val result = resolver.resolveRecording(docOlder, docNewer)
            val resolvedNote = result.body["postRecordingNote"]?.jsonPrimitive?.content ?: ""

            // The resolved note must contain "[deviceIdA]: noteA"
            val expectedOlderPrefix = "[$deviceIdA]: $noteA"
            val expectedNewerPrefix = "[$deviceIdB]: $noteB"

            assertTrue(
                resolvedNote.contains(expectedOlderPrefix),
                "Resolved note must contain '[$deviceIdA]: $noteA'. Got: '$resolvedNote'"
            )
            assertTrue(
                resolvedNote.contains(expectedNewerPrefix),
                "Resolved note must contain '[$deviceIdB]: $noteB'. Got: '$resolvedNote'"
            )
        }
    }

    /**
     * Property 5: Resolved note format matches exactly the expected structure.
     *
     * For two non-empty notes with distinct timestamps, the resolved note SHALL be:
     * "[older-deviceId]: older-note\n[newer-deviceId]: newer-note"
     *
     * **Validates: Requirements 5.3**
     */
    @Test
    fun property5_resolvedNote_exactFormat() = runTest {
        checkAll(config, arbDistinctDeviceIdPair, arbDistinctTimestampPair, arbNonEmptyNote, arbNonEmptyNote) {
            (deviceIdA, deviceIdB), (olderTs, newerTs), noteA, noteB ->

            val resolver = ConflictResolver("test-device")

            val docOlder = makeRecordingDoc(deviceId = deviceIdA, modifiedAt = olderTs, note = noteA)
            val docNewer = makeRecordingDoc(deviceId = deviceIdB, modifiedAt = newerTs, note = noteB)

            val result = resolver.resolveRecording(docOlder, docNewer)
            val resolvedNote = result.body["postRecordingNote"]?.jsonPrimitive?.content ?: ""

            val expected = "[$deviceIdA]: $noteA\n[$deviceIdB]: $noteB"

            assertEquals(
                expected,
                resolvedNote,
                "Resolved note must be '[older-deviceId]: older-note\\n[newer-deviceId]: newer-note'"
            )
        }
    }

    /**
     * Property 5: Order is determined by modifiedAt regardless of which document is local vs remote.
     *
     * Passing (docOlder, docNewer) and (docNewer, docOlder) to resolveRecording
     * must produce the same resolved note.
     *
     * **Validates: Requirements 5.3**
     */
    @Test
    fun property5_resolvedNote_orderIndependentOfLocalRemotePosition() = runTest {
        checkAll(config, arbDistinctDeviceIdPair, arbDistinctTimestampPair, arbNonEmptyNote, arbNonEmptyNote) {
            (deviceIdA, deviceIdB), (olderTs, newerTs), noteA, noteB ->

            val resolver = ConflictResolver("test-device")

            val docOlder = makeRecordingDoc(deviceId = deviceIdA, modifiedAt = olderTs, note = noteA)
            val docNewer = makeRecordingDoc(deviceId = deviceIdB, modifiedAt = newerTs, note = noteB)

            // Resolve in both argument orders
            val resultAB = resolver.resolveRecording(docOlder, docNewer)
            val resultBA = resolver.resolveRecording(docNewer, docOlder)

            val noteAB = resultAB.body["postRecordingNote"]?.jsonPrimitive?.content ?: ""
            val noteBA = resultBA.body["postRecordingNote"]?.jsonPrimitive?.content ?: ""

            assertEquals(
                noteAB,
                noteBA,
                "Note concatenation ordering must depend only on modifiedAt, not argument position. " +
                    "AB='$noteAB', BA='$noteBA'"
            )
        }
    }
}
