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

// Feature: multi-device-sync, Property 6: Tags/highlights union merge

/**
 * Property-based tests for tags/highlights union merge.
 *
 * For any two conflicting Recording documents, the resolved tags list SHALL equal
 * the set union of both tag lists (case-sensitive), and the resolved highlights list
 * SHALL equal the set union of both highlight lists.
 *
 * **Validates: Requirements 5.4**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class UnionMergePropertyTest {

    private val config = PropTestConfig(iterations = 100)

    // =========================================================================
    // Generators
    // =========================================================================

    private val arbDeviceId: Arb<String> = Arb.string(5..20)
        .filter { it.isNotBlank() }

    private val arbTimestamp: Arb<Long> = Arb.long(1L..9_999_999_999_999L)

    /** Tags are case-sensitive strings; include mixed-case to exercise case sensitivity. */
    private val arbTag: Arb<String> = Arb.of(
        "sprint", "Sprint", "SPRINT", "planning", "Planning",
        "bug", "Bug", "feature", "Feature", "docs", "Docs",
        "release", "Release", "hotfix", "Hotfix", "review", "Review",
        "api", "API", "ui", "UI", "backend", "Backend"
    )

    private val arbTagList: Arb<List<String>> = Arb.list(arbTag, 0..8)

    private val arbHighlight: Arb<Long> = Arb.long(0L..86_400_000L)

    private val arbHighlightList: Arb<List<Long>> = Arb.list(arbHighlight, 0..8)

    /**
     * Generate a recording CouchDocument body with the given tags and highlights.
     */
    private fun buildRecordingBody(tags: List<String>, highlights: List<Long>): JsonObject {
        return buildJsonObject {
            put("id", JsonPrimitive("rec-001"))
            put("filePath", JsonPrimitive("/audio/rec-001.wav"))
            put("durationMs", JsonPrimitive(60000L))
            put("createdAt", JsonPrimitive(1700000000000L))
            put("title", JsonPrimitive("Test Recording"))
            put("description", JsonPrimitive("A test recording"))
            put("template", JsonPrimitive("GENERAL"))
            put("tags", JsonArray(tags.map { JsonPrimitive(it) }))
            put("folder", JsonPrimitive(""))
            put("highlights", JsonArray(highlights.map { JsonPrimitive(it) }))
            put("attachments", JsonArray(emptyList()))
            put("postRecordingNote", JsonPrimitive(""))
        }
    }

    /**
     * Build a recording CouchDocument with specified tags, highlights, deviceId, and modifiedAt.
     */
    private fun buildRecordingDoc(
        tags: List<String>,
        highlights: List<Long>,
        deviceId: String,
        modifiedAt: Long
    ): CouchDocument {
        return CouchDocument(
            id = "recording:rec-001",
            rev = "1-abc",
            type = "recording",
            deviceId = deviceId,
            modifiedAt = modifiedAt,
            body = buildRecordingBody(tags, highlights)
        )
    }

    // =========================================================================
    // Property Tests
    // =========================================================================

    /**
     * Property 6: Tags union merge (case-sensitive)
     *
     * For any two conflicting Recording documents with varying tag lists,
     * the resolved tags SHALL equal the set union of both tag lists (case-sensitive).
     *
     * **Validates: Requirements 5.4**
     */
    @Test
    fun property6_tags_resolvedAsSetUnion_caseSensitive() = runTest {
        checkAll(config, arbTagList, arbTagList, arbDeviceId, arbDeviceId, arbTimestamp, arbTimestamp) {
            localTags, remoteTags, localDeviceId, remoteDeviceId, localTs, remoteTs ->

            val resolver = ConflictResolver(localDeviceId)

            val local = buildRecordingDoc(localTags, emptyList(), localDeviceId, localTs)
            val remote = buildRecordingDoc(remoteTags, emptyList(), remoteDeviceId, remoteTs)

            val resolved = resolver.resolveRecording(local, remote)
            val resolvedBody = resolved.body

            // Extract resolved tags
            val resolvedTags = resolvedBody["tags"]!!.jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()

            // Expected: set union of both tag lists (case-sensitive)
            val expectedTags = (localTags + remoteTags).toSet()

            assertEquals(
                expectedTags,
                resolvedTags,
                "Resolved tags must be the set union of local tags $localTags and remote tags $remoteTags. " +
                    "Expected $expectedTags but got $resolvedTags"
            )
        }
    }

    /**
     * Property 6: Highlights union merge
     *
     * For any two conflicting Recording documents with varying highlight lists,
     * the resolved highlights SHALL equal the set union of both highlight lists.
     *
     * **Validates: Requirements 5.4**
     */
    @Test
    fun property6_highlights_resolvedAsSetUnion() = runTest {
        checkAll(config, arbHighlightList, arbHighlightList, arbDeviceId, arbDeviceId, arbTimestamp, arbTimestamp) {
            localHighlights, remoteHighlights, localDeviceId, remoteDeviceId, localTs, remoteTs ->

            val resolver = ConflictResolver(localDeviceId)

            val local = buildRecordingDoc(emptyList(), localHighlights, localDeviceId, localTs)
            val remote = buildRecordingDoc(emptyList(), remoteHighlights, remoteDeviceId, remoteTs)

            val resolved = resolver.resolveRecording(local, remote)
            val resolvedBody = resolved.body

            // Extract resolved highlights
            val resolvedHighlights = resolvedBody["highlights"]!!.jsonArray
                .map { it.jsonPrimitive.long }
                .toSet()

            // Expected: set union of both highlight lists
            val expectedHighlights = (localHighlights + remoteHighlights).toSet()

            assertEquals(
                expectedHighlights,
                resolvedHighlights,
                "Resolved highlights must be the set union of local highlights $localHighlights " +
                    "and remote highlights $remoteHighlights. " +
                    "Expected $expectedHighlights but got $resolvedHighlights"
            )
        }
    }

    /**
     * Property 6: Combined tags and highlights union merge
     *
     * When both tags and highlights vary between the two conflicting documents,
     * both are resolved correctly as set unions simultaneously.
     *
     * **Validates: Requirements 5.4**
     */
    @Test
    fun property6_tagsAndHighlights_bothResolvedAsSetUnion() = runTest {
        checkAll(config, arbTagList, arbTagList, arbHighlightList, arbHighlightList, arbDeviceId, arbDeviceId) {
            localTags, remoteTags, localHighlights, remoteHighlights, localDeviceId, remoteDeviceId ->

            val localTs = 1700000000000L
            val remoteTs = 1700000001000L

            val resolver = ConflictResolver(localDeviceId)

            val local = buildRecordingDoc(localTags, localHighlights, localDeviceId, localTs)
            val remote = buildRecordingDoc(remoteTags, remoteHighlights, remoteDeviceId, remoteTs)

            val resolved = resolver.resolveRecording(local, remote)
            val resolvedBody = resolved.body

            // Verify tags union
            val resolvedTags = resolvedBody["tags"]!!.jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()
            val expectedTags = (localTags + remoteTags).toSet()

            assertEquals(
                expectedTags,
                resolvedTags,
                "Resolved tags must be the set union of both input tag lists (case-sensitive)"
            )

            // Verify highlights union
            val resolvedHighlights = resolvedBody["highlights"]!!.jsonArray
                .map { it.jsonPrimitive.long }
                .toSet()
            val expectedHighlights = (localHighlights + remoteHighlights).toSet()

            assertEquals(
                expectedHighlights,
                resolvedHighlights,
                "Resolved highlights must be the set union of both input highlight lists"
            )
        }
    }

    /**
     * Property 6: Tags union merge preserves case sensitivity
     *
     * Tags that differ only in case are treated as distinct entries in the union.
     * For example, "Sprint" and "sprint" are two separate tags.
     *
     * **Validates: Requirements 5.4**
     */
    @Test
    fun property6_tags_caseSensitive_distinctEntries() = runTest {
        checkAll(config, arbDeviceId, arbDeviceId, arbTimestamp, arbTimestamp) {
            localDeviceId, remoteDeviceId, localTs, remoteTs ->

            val resolver = ConflictResolver(localDeviceId)

            // Explicitly use case variants
            val localTags = listOf("sprint", "Bug", "feature")
            val remoteTags = listOf("Sprint", "bug", "Feature")

            val local = buildRecordingDoc(localTags, emptyList(), localDeviceId, localTs)
            val remote = buildRecordingDoc(remoteTags, emptyList(), remoteDeviceId, remoteTs)

            val resolved = resolver.resolveRecording(local, remote)
            val resolvedBody = resolved.body

            val resolvedTags = resolvedBody["tags"]!!.jsonArray
                .map { it.jsonPrimitive.content }
                .toSet()

            // All 6 tags should be present since they differ by case
            val expectedTags = setOf("sprint", "Sprint", "Bug", "bug", "feature", "Feature")

            assertEquals(
                expectedTags,
                resolvedTags,
                "Case-different tags must be treated as distinct: expected $expectedTags but got $resolvedTags"
            )
        }
    }
}
