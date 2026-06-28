package com.jeeves.shared.sync

import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.*
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals

// Feature: multi-device-sync, Property 3: Revision uniqueness on write

/**
 * Property-based tests for revision uniqueness on write.
 *
 * For any document in the LocalDocumentStore, each call to put() SHALL produce
 * a _rev value that is distinct from all previous _rev values for that document.
 *
 * **Validates: Requirements 2.6**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class RevisionUniquenessPropertyTest {

    private val config = PropTestConfig(iterations = 100)

    // =========================================================================
    // Generators
    // =========================================================================

    private val arbDocId: Arb<String> = Arb.string(1..20)
        .filter { it.isNotBlank() && !it.contains(':') && !it.contains('/') }
        .map { "recording:$it" }

    private val arbJsonPrimitive: Arb<JsonElement> = Arb.choice(
        Arb.string(0..50).map { JsonPrimitive(it) },
        Arb.int().map { JsonPrimitive(it) },
        Arb.boolean().map { JsonPrimitive(it) }
    )

    private val arbJsonObject: Arb<JsonObject> = Arb.list(
        Arb.bind(
            Arb.string(1..10).filter { it.isNotBlank() },
            arbJsonPrimitive
        ) { key, value -> key to value },
        1..5
    ).map { entries -> JsonObject(entries.toMap()) }

    /** Number of put() calls per test iteration (3..10 as specified in the task) */
    private val arbPutCount: Arb<Int> = Arb.int(3..10)

    /** Generate a list of distinct JsonObject bodies for successive puts */
    private fun arbBodies(count: Int): Arb<List<JsonObject>> =
        Arb.list(arbJsonObject, count..count)

    // =========================================================================
    // Property Tests
    // =========================================================================

    /**
     * Property 3: Revision uniqueness on write
     *
     * For any document, calling put() multiple times with different body content
     * SHALL produce _rev values that are all distinct from each other.
     *
     * **Validates: Requirements 2.6**
     */
    @Test
    fun property3_allRevisions_areDistinct_acrossMultiplePuts() = runTest {
        checkAll(config, arbDocId, arbPutCount) { docId, putCount ->
            // Create a fresh temp directory and store for each iteration
            val tempDir = createTempDirectory("rev-uniqueness-test").toFile()
            try {
                val store = LocalDocumentStore(tempDir)
                val revisions = mutableListOf<String>()

                // Perform N puts with varying body content
                var currentRev: String? = null
                for (i in 1..putCount) {
                    // Generate a body that varies with each iteration
                    val body = JsonObject(mapOf(
                        "iteration" to JsonPrimitive(i),
                        "data" to JsonPrimitive("content-$i-${System.nanoTime()}")
                    ))

                    val doc = CouchDocument(
                        id = docId,
                        rev = currentRev,
                        type = "recording",
                        deviceId = "test-device",
                        modifiedAt = System.currentTimeMillis(),
                        body = body
                    )

                    val saved = store.put(doc)
                    revisions.add(saved.rev!!)
                    currentRev = saved.rev
                }

                // Assert all revisions are distinct
                val distinctRevisions = revisions.toSet()
                assertEquals(
                    revisions.size,
                    distinctRevisions.size,
                    "All _rev values must be distinct across $putCount puts. " +
                        "Got ${revisions.size} revisions but only ${distinctRevisions.size} unique. " +
                        "Revisions: $revisions"
                )
            } finally {
                // Clean up temp directory
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * Property 3: Revision uniqueness on write (same content)
     *
     * Even when the same body content is written multiple times, each put()
     * SHALL produce a distinct _rev because the generation number increments.
     *
     * **Validates: Requirements 2.6**
     */
    @Test
    fun property3_allRevisions_areDistinct_evenWithSameContent() = runTest {
        checkAll(config, arbDocId, arbJsonObject, arbPutCount) { docId, body, putCount ->
            val tempDir = createTempDirectory("rev-uniqueness-same-content").toFile()
            try {
                val store = LocalDocumentStore(tempDir)
                val revisions = mutableListOf<String>()

                var currentRev: String? = null
                for (i in 1..putCount) {
                    val doc = CouchDocument(
                        id = docId,
                        rev = currentRev,
                        type = "recording",
                        deviceId = "test-device",
                        modifiedAt = 1000L * i,
                        body = body // same body every time
                    )

                    val saved = store.put(doc)
                    revisions.add(saved.rev!!)
                    currentRev = saved.rev
                }

                // Assert all revisions are distinct even with same content
                val distinctRevisions = revisions.toSet()
                assertEquals(
                    revisions.size,
                    distinctRevisions.size,
                    "All _rev values must be distinct even when body content is identical. " +
                        "Got ${revisions.size} revisions but only ${distinctRevisions.size} unique. " +
                        "Revisions: $revisions"
                )
            } finally {
                tempDir.deleteRecursively()
            }
        }
    }
}
