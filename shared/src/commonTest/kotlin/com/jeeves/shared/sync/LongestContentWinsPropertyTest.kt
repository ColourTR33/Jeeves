package com.jeeves.shared.sync

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

// Feature: multi-device-sync, Property 7: Longest-content-wins for text documents

/**
 * Property-based tests for longest-content-wins conflict resolution on
 * TranscriptionResult and SummaryResult documents.
 *
 * For any two conflicting TranscriptionResult or SummaryResult documents,
 * the ConflictResolver SHALL select the version with the longer text content
 * (character count) as primary; and if lengths are equal, SHALL select the
 * version with the more recent modifiedAt timestamp.
 *
 * **Validates: Requirements 5.5**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class LongestContentWinsPropertyTest {

    private val config = PropTestConfig(iterations = 100)

    // =========================================================================
    // Generators
    // =========================================================================

    private val arbDeviceId: Arb<String> = Arb.string(5..20)
        .filter { it.isNotBlank() }

    private val arbTimestamp: Arb<Long> = Arb.long(1L..9_999_999_999_999L)

    /** Generate text of varying lengths for transcription content. */
    private val arbText: Arb<String> = Arb.string(0..500)

    /**
     * Generate a pair of texts with distinct lengths (first is strictly shorter than second).
     */
    private val arbDistinctLengthTextPair: Arb<Pair<String, String>> = Arb.bind(
        Arb.string(0..200),
        Arb.string(1..100)
    ) { base, extra ->
        base to (base + extra)
    }

    /**
     * Generate a pair of texts with exactly equal length.
     */
    private fun arbEqualLengthTextPair(): Arb<Pair<String, String>> = Arb.bind(
        Arb.int(1..200),
        Arb.of('a'..'z'),
        Arb.of('A'..'Z')
    ) { length, charA, charB ->
        val textA = charA.toString().repeat(length)
        val textB = charB.toString().repeat(length)
        textA to textB
    }

    /**
     * Generate a pair of distinct timestamps (first < second).
     */
    private val arbDistinctTimestampPair: Arb<Pair<Long, Long>> = Arb.bind(
        Arb.long(1L..4_999_999_999_999L),
        Arb.long(1L..5_000_000_000_000L)
    ) { a, offset ->
        a to (a + offset)
    }

    /**
     * Build a transcription CouchDocument with the specified text content.
     */
    private fun buildTranscriptionDoc(
        text: String,
        deviceId: String,
        modifiedAt: Long
    ): CouchDocument {
        val body = buildJsonObject {
            put("recordingId", JsonPrimitive("rec-001"))
            put("text", JsonPrimitive(text))
            put("segments", JsonArray(emptyList()))
            put("language", JsonPrimitive("en"))
            put("durationMs", JsonPrimitive(60000L))
            put("diarizationUnavailable", JsonPrimitive(false))
        }
        return CouchDocument(
            id = "transcription:rec-001",
            rev = "1-${deviceId.hashCode().toUInt().toString(16)}",
            type = "transcription",
            deviceId = deviceId,
            modifiedAt = modifiedAt,
            body = body
        )
    }

    /**
     * Build a summary CouchDocument with the specified summary content.
     */
    private fun buildSummaryDoc(
        summary: String,
        deviceId: String,
        modifiedAt: Long
    ): CouchDocument {
        val body = buildJsonObject {
            put("recordingId", JsonPrimitive("rec-001"))
            put("summary", JsonPrimitive(summary))
            put("keyPoints", JsonArray(emptyList()))
            put("actionItems", JsonArray(emptyList()))
            put("questions", JsonArray(emptyList()))
            put("tags", JsonArray(emptyList()))
            put("modelUsed", JsonPrimitive("qwen3:8b"))
            put("recommendedQuestions", JsonArray(emptyList()))
        }
        return CouchDocument(
            id = "summary:rec-001",
            rev = "1-${deviceId.hashCode().toUInt().toString(16)}",
            type = "summary",
            deviceId = deviceId,
            modifiedAt = modifiedAt,
            body = body
        )
    }

    // =========================================================================
    // Property Tests
    // =========================================================================

    /**
     * Property 7a: For transcription documents with distinct text lengths,
     * the version with the longer text content wins regardless of timestamps.
     *
     * **Validates: Requirements 5.5**
     */
    @Test
    fun property7_transcription_longerTextWins() = runTest {
        checkAll(
            config,
            arbDistinctLengthTextPair,
            arbDeviceId,
            arbDeviceId,
            arbTimestamp,
            arbTimestamp
        ) { (shorterText, longerText), localDeviceId, remoteDeviceId, localTs, remoteTs ->

            val resolver = ConflictResolver(localDeviceId)

            val shorterDoc = buildTranscriptionDoc(shorterText, localDeviceId, localTs)
            val longerDoc = buildTranscriptionDoc(longerText, remoteDeviceId, remoteTs)

            // Test with shorter as local, longer as remote
            val result1 = resolver.resolveTextDocument(shorterDoc, longerDoc)
            assertEquals(
                longerText,
                result1.body["text"]?.jsonPrimitive?.content,
                "Longer text (${longerText.length} chars) should win over shorter text " +
                    "(${shorterText.length} chars) regardless of timestamps"
            )

            // Test with longer as local, shorter as remote
            val result2 = resolver.resolveTextDocument(longerDoc, shorterDoc)
            assertEquals(
                longerText,
                result2.body["text"]?.jsonPrimitive?.content,
                "Longer text (${longerText.length} chars) should win over shorter text " +
                    "(${shorterText.length} chars) regardless of argument order"
            )
        }
    }

    /**
     * Property 7b: For summary documents with distinct text lengths,
     * the version with the longer summary content wins regardless of timestamps.
     *
     * **Validates: Requirements 5.5**
     */
    @Test
    fun property7_summary_longerTextWins() = runTest {
        checkAll(
            config,
            arbDistinctLengthTextPair,
            arbDeviceId,
            arbDeviceId,
            arbTimestamp,
            arbTimestamp
        ) { (shorterText, longerText), localDeviceId, remoteDeviceId, localTs, remoteTs ->

            val resolver = ConflictResolver(localDeviceId)

            val shorterDoc = buildSummaryDoc(shorterText, localDeviceId, localTs)
            val longerDoc = buildSummaryDoc(longerText, remoteDeviceId, remoteTs)

            // Test with shorter as local, longer as remote
            val result1 = resolver.resolveTextDocument(shorterDoc, longerDoc)
            assertEquals(
                longerText,
                result1.body["summary"]?.jsonPrimitive?.content,
                "Longer summary (${longerText.length} chars) should win over shorter summary " +
                    "(${shorterText.length} chars) regardless of timestamps"
            )

            // Test with longer as local, shorter as remote
            val result2 = resolver.resolveTextDocument(longerDoc, shorterDoc)
            assertEquals(
                longerText,
                result2.body["summary"]?.jsonPrimitive?.content,
                "Longer summary (${longerText.length} chars) should win over shorter summary " +
                    "(${shorterText.length} chars) regardless of argument order"
            )
        }
    }

    /**
     * Property 7c: For transcription documents with equal-length text,
     * the version with the more recent modifiedAt timestamp wins.
     *
     * **Validates: Requirements 5.5**
     */
    @Test
    fun property7_transcription_equalLength_moreRecentTimestampWins() = runTest {
        checkAll(
            config,
            arbEqualLengthTextPair(),
            arbDeviceId,
            arbDeviceId,
            arbDistinctTimestampPair
        ) { (textA, textB), deviceIdA, deviceIdB, (olderTs, newerTs) ->

            val resolver = ConflictResolver(deviceIdA)

            val olderDoc = buildTranscriptionDoc(textA, deviceIdA, olderTs)
            val newerDoc = buildTranscriptionDoc(textB, deviceIdB, newerTs)

            // Texts should have equal length by construction
            assertEquals(textA.length, textB.length,
                "Test setup: texts must have equal length")

            // Test with older as local, newer as remote
            val result1 = resolver.resolveTextDocument(olderDoc, newerDoc)
            assertEquals(
                newerTs,
                result1.modifiedAt,
                "On equal text length (${textA.length} chars), more recent timestamp ($newerTs) " +
                    "should win over older ($olderTs)"
            )

            // Test with newer as local, older as remote
            val result2 = resolver.resolveTextDocument(newerDoc, olderDoc)
            assertEquals(
                newerTs,
                result2.modifiedAt,
                "On equal text length (${textA.length} chars), more recent timestamp ($newerTs) " +
                    "should win over older ($olderTs) regardless of argument order"
            )
        }
    }

    /**
     * Property 7d: For summary documents with equal-length text,
     * the version with the more recent modifiedAt timestamp wins.
     *
     * **Validates: Requirements 5.5**
     */
    @Test
    fun property7_summary_equalLength_moreRecentTimestampWins() = runTest {
        checkAll(
            config,
            arbEqualLengthTextPair(),
            arbDeviceId,
            arbDeviceId,
            arbDistinctTimestampPair
        ) { (summaryA, summaryB), deviceIdA, deviceIdB, (olderTs, newerTs) ->

            val resolver = ConflictResolver(deviceIdA)

            val olderDoc = buildSummaryDoc(summaryA, deviceIdA, olderTs)
            val newerDoc = buildSummaryDoc(summaryB, deviceIdB, newerTs)

            // Summaries should have equal length by construction
            assertEquals(summaryA.length, summaryB.length,
                "Test setup: summaries must have equal length")

            // Test with older as local, newer as remote
            val result1 = resolver.resolveTextDocument(olderDoc, newerDoc)
            assertEquals(
                newerTs,
                result1.modifiedAt,
                "On equal summary length (${summaryA.length} chars), more recent timestamp ($newerTs) " +
                    "should win over older ($olderTs)"
            )

            // Test with newer as local, older as remote
            val result2 = resolver.resolveTextDocument(newerDoc, olderDoc)
            assertEquals(
                newerTs,
                result2.modifiedAt,
                "On equal summary length (${summaryA.length} chars), more recent timestamp ($newerTs) " +
                    "should win over older ($olderTs) regardless of argument order"
            )
        }
    }
}
