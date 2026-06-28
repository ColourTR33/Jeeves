package com.jeeves.shared.sync

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.test.Test
import kotlin.test.assertEquals

// Feature: multi-device-sync, Property 4: Last-write-wins correctness for title/description

/**
 * Property-based tests for last-write-wins conflict resolution on Recording documents.
 *
 * For any two conflicting Recording documents with distinct modifiedAt timestamps,
 * the ConflictResolver SHALL select the title and description from the document with
 * the greater modifiedAt value; and if timestamps are identical, SHALL select from the
 * document whose deviceId is lexicographically greater.
 *
 * **Validates: Requirements 5.2**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class LastWriteWinsPropertyTest {

    private val config = PropTestConfig(iterations = 100)

    private val resolver = ConflictResolver("resolver-device")

    // =========================================================================
    // Generators
    // =========================================================================

    private val arbDeviceId: Arb<String> = Arb.string(5..40)
        .filter { it.isNotBlank() }

    private val arbTimestamp: Arb<Long> = Arb.long(0L..9_999_999_999_999L)

    private val arbTitle: Arb<String> = Arb.string(0..100)

    private val arbDescription: Arb<String> = Arb.string(0..200)

    /**
     * Generates a pair of distinct timestamps guaranteed to not be equal.
     */
    private val arbDistinctTimestampPair: Arb<Pair<Long, Long>> = Arb.bind(
        Arb.long(0L..4_999_999_999_999L),
        Arb.long(1L..5_000_000_000_000L)
    ) { a, offset ->
        // Ensure they are distinct by using a and a + offset
        a to (a + offset)
    }

    /**
     * Generates a pair of distinct deviceIds guaranteed to not be equal.
     */
    private val arbDistinctDeviceIdPair: Arb<Pair<String, String>> = Arb.bind(
        Arb.string(5..20).filter { it.isNotBlank() },
        Arb.string(5..20).filter { it.isNotBlank() }
    ) { a, b ->
        if (a == b) (a + "X") to b else a to b
    }

    /**
     * Creates a CouchDocument of type "recording" with the given parameters.
     */
    private fun makeRecordingDoc(
        deviceId: String,
        modifiedAt: Long,
        title: String,
        description: String
    ): CouchDocument {
        val body = buildJsonObject {
            put("id", "rec-prop-test")
            put("filePath", "/audio/test.wav")
            put("durationMs", 60000L)
            put("createdAt", 1000L)
            put("title", title)
            put("description", description)
            put("template", "GENERAL")
            put("tags", JsonArray(emptyList()))
            put("folder", "")
            put("highlights", JsonArray(emptyList()))
            put("attachments", JsonArray(emptyList()))
            put("postRecordingNote", "")
        }
        return CouchDocument(
            id = "recording:rec-prop-test",
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
     * Property 4a: When two Recording documents have distinct modifiedAt timestamps,
     * the resolved title and description SHALL come from the document with the greater
     * modifiedAt value.
     *
     * **Validates: Requirements 5.2**
     */
    @Test
    fun property4_distinctTimestamps_greaterModifiedAtWins() = runTest {
        checkAll(
            config,
            arbDistinctTimestampPair,
            arbDeviceId,
            arbDeviceId,
            arbTitle,
            arbTitle,
            arbDescription
        ) { (ts1, ts2), deviceId1, deviceId2, title1, title2, desc1 ->
            // ts1 < ts2 guaranteed by construction (ts2 = ts1 + positive offset)
            val desc2 = desc1 + " modified" // ensure different descriptions

            val olderDoc = makeRecordingDoc(
                deviceId = deviceId1,
                modifiedAt = ts1,
                title = title1,
                description = desc1
            )
            val newerDoc = makeRecordingDoc(
                deviceId = deviceId2,
                modifiedAt = ts2,
                title = title2,
                description = desc2
            )

            // Test both orderings: local=older/remote=newer and local=newer/remote=older
            val result1 = resolver.resolveRecording(olderDoc, newerDoc)
            assertEquals(
                title2,
                result1.body["title"]?.jsonPrimitive?.content,
                "When remote has greater modifiedAt ($ts2 > $ts1), its title should win"
            )
            assertEquals(
                desc2,
                result1.body["description"]?.jsonPrimitive?.content,
                "When remote has greater modifiedAt ($ts2 > $ts1), its description should win"
            )

            val result2 = resolver.resolveRecording(newerDoc, olderDoc)
            assertEquals(
                title2,
                result2.body["title"]?.jsonPrimitive?.content,
                "When local has greater modifiedAt ($ts2 > $ts1), its title should win"
            )
            assertEquals(
                desc2,
                result2.body["description"]?.jsonPrimitive?.content,
                "When local has greater modifiedAt ($ts2 > $ts1), its description should win"
            )
        }
    }

    /**
     * Property 4b: When two Recording documents have identical modifiedAt timestamps,
     * the resolved title and description SHALL come from the document whose deviceId
     * is lexicographically greater.
     *
     * **Validates: Requirements 5.2**
     */
    @Test
    fun property4_identicalTimestamps_greaterDeviceIdWins() = runTest {
        checkAll(
            config,
            arbTimestamp,
            arbDistinctDeviceIdPair,
            arbTitle,
            arbTitle,
            arbDescription,
            arbDescription
        ) { timestamp, (deviceIdA, deviceIdB), titleA, titleB, descA, descB ->
            val docA = makeRecordingDoc(
                deviceId = deviceIdA,
                modifiedAt = timestamp,
                title = titleA,
                description = descA
            )
            val docB = makeRecordingDoc(
                deviceId = deviceIdB,
                modifiedAt = timestamp,
                title = titleB,
                description = descB
            )

            val result = resolver.resolveRecording(docA, docB)

            // The winner is the document with the lexicographically greater deviceId
            val expectedTitle: String
            val expectedDesc: String
            if (deviceIdA > deviceIdB) {
                expectedTitle = titleA
                expectedDesc = descA
            } else {
                expectedTitle = titleB
                expectedDesc = descB
            }

            assertEquals(
                expectedTitle,
                result.body["title"]?.jsonPrimitive?.content,
                "On timestamp tie ($timestamp), device with greater deviceId should win. " +
                    "deviceIdA='$deviceIdA', deviceIdB='$deviceIdB'"
            )
            assertEquals(
                expectedDesc,
                result.body["description"]?.jsonPrimitive?.content,
                "On timestamp tie ($timestamp), device with greater deviceId should win. " +
                    "deviceIdA='$deviceIdA', deviceIdB='$deviceIdB'"
            )
        }
    }
}
