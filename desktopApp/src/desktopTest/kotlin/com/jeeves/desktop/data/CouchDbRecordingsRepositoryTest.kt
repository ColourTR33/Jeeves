package com.jeeves.desktop.data

import com.jeeves.shared.domain.*
import com.jeeves.shared.sync.LocalDocumentStore
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.*

/**
 * Unit tests for CouchDbRecordingsRepository.
 *
 * Verifies CRUD operations produce correct CouchDB documents and that domain model
 * fields are preserved through save/read cycles using an in-memory LocalDocumentStore
 * backed by a temporary directory.
 *
 * **Validates: Requirements 2.2, 2.5**
 */
class CouchDbRecordingsRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var store: LocalDocumentStore
    private lateinit var repository: CouchDbRecordingsRepository

    private val testDeviceId = "test-device-001"

    @BeforeTest
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "couch-test-${System.nanoTime()}")
        tempDir.mkdirs()
        store = LocalDocumentStore(tempDir)
        repository = CouchDbRecordingsRepository(store, testDeviceId)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // --- Helper factories ---

    private fun createRecording(
        id: String = "rec-001",
        title: String = "Sprint Planning",
        description: String = "Weekly sprint planning",
        template: MeetingTemplate = MeetingTemplate.STANDUP,
        tags: List<String> = listOf("sprint", "planning"),
        folder: String = "work",
        highlights: List<Long> = listOf(120000L, 1800000L),
        attachments: List<Attachment> = listOf(
            Attachment(
                id = "att-1",
                filePath = "/tmp/screenshot1.png",
                timestampMs = 60000L,
                caption = "Architecture diagram"
            )
        ),
        postRecordingNote: String = "Follow up on API redesign"
    ) = Recording(
        id = id,
        filePath = "/Users/mark/audio/$id.wav",
        durationMs = 3600000L,
        createdAt = 1700000000000L,
        title = title,
        description = description,
        template = template,
        tags = tags,
        folder = folder,
        highlights = highlights,
        attachments = attachments,
        postRecordingNote = postRecordingNote
    )

    private fun createTranscription(recordingId: String = "rec-001") = TranscriptionResult(
        recordingId = recordingId,
        text = "Welcome everyone to sprint planning...",
        segments = listOf(
            TranscriptionSegment(startMs = 0, endMs = 5000, text = "Welcome everyone", speaker = "Speaker 1"),
            TranscriptionSegment(startMs = 5001, endMs = 10000, text = "Let's begin", speaker = null)
        ),
        language = "en",
        durationMs = 3600000L,
        diarizationUnavailable = false
    )

    private fun createSummary(recordingId: String = "rec-001") = SummaryResult(
        recordingId = recordingId,
        summary = "The team discussed sprint goals and API redesign timeline.",
        keyPoints = listOf("API redesign timeline agreed", "New hire onboarding plan"),
        actionItems = listOf("Mark: Draft API spec by Friday"),
        questions = listOf("Should we use GraphQL or REST?"),
        tags = listOf("sprint", "planning", "api"),
        modelUsed = "qwen3:8b",
        recommendedQuestions = listOf("What are the risks?", "Who is responsible?"),
        qualityRating = QualityRating(
            pacing = 4,
            questions = 3,
            goalSetting = 5,
            nextSteps = 4,
            overall = 4.0
        )
    )

    // =========================================================================
    // 1. saveRecording + getRecording round-trip preserves all fields
    // =========================================================================

    @Test
    fun saveRecording_getRecording_roundTrip_preservesAllFields() = runTest {
        val recording = createRecording()

        repository.saveRecording(recording)
        val retrieved = repository.getRecording(recording.id)

        assertNotNull(retrieved)
        assertEquals(recording.id, retrieved.id)
        assertEquals(recording.filePath, retrieved.filePath)
        assertEquals(recording.durationMs, retrieved.durationMs)
        assertEquals(recording.createdAt, retrieved.createdAt)
        assertEquals(recording.title, retrieved.title)
        assertEquals(recording.description, retrieved.description)
        assertEquals(recording.template, retrieved.template)
        assertEquals(recording.tags, retrieved.tags)
        assertEquals(recording.folder, retrieved.folder)
        assertEquals(recording.highlights, retrieved.highlights)
        assertEquals(recording.attachments, retrieved.attachments)
        assertEquals(recording.postRecordingNote, retrieved.postRecordingNote)
    }

    // =========================================================================
    // 2. getRecordings returns all saved recordings
    // =========================================================================

    @Test
    fun getRecordings_returnsAllSavedRecordings() = runTest {
        val rec1 = createRecording(id = "rec-001", title = "Meeting 1")
        val rec2 = createRecording(id = "rec-002", title = "Meeting 2")
        val rec3 = createRecording(id = "rec-003", title = "Meeting 3")

        repository.saveRecording(rec1)
        repository.saveRecording(rec2)
        repository.saveRecording(rec3)

        val all = repository.getRecordings()
        assertEquals(3, all.size)
        assertTrue(all.any { it.id == "rec-001" })
        assertTrue(all.any { it.id == "rec-002" })
        assertTrue(all.any { it.id == "rec-003" })
    }

    // =========================================================================
    // 3. getRecording returns null for non-existent ID
    // =========================================================================

    @Test
    fun getRecording_nonExistentId_returnsNull() = runTest {
        val result = repository.getRecording("does-not-exist")
        assertNull(result)
    }

    // =========================================================================
    // 4. updateRecording modifies existing recording
    // =========================================================================

    @Test
    fun updateRecording_modifiesExistingRecording() = runTest {
        val original = createRecording(title = "Original Title")
        repository.saveRecording(original)

        val updated = original.copy(title = "Updated Title", description = "New description")
        repository.updateRecording(updated)

        val retrieved = repository.getRecording(original.id)
        assertNotNull(retrieved)
        assertEquals("Updated Title", retrieved.title)
        assertEquals("New description", retrieved.description)
        // Unchanged fields preserved
        assertEquals(original.durationMs, retrieved.durationMs)
        assertEquals(original.tags, retrieved.tags)
    }

    // =========================================================================
    // 5. updateRecordingNote updates only the note field
    // =========================================================================

    @Test
    fun updateRecordingNote_updatesOnlyNoteField() = runTest {
        val recording = createRecording(postRecordingNote = "Original note")
        repository.saveRecording(recording)

        repository.updateRecordingNote(recording.id, "Updated note content")

        val retrieved = repository.getRecording(recording.id)
        assertNotNull(retrieved)
        assertEquals("Updated note content", retrieved.postRecordingNote)
        // Other fields unchanged
        assertEquals(recording.title, retrieved.title)
        assertEquals(recording.tags, retrieved.tags)
        assertEquals(recording.highlights, retrieved.highlights)
    }

    // =========================================================================
    // 6. deleteRecording removes the recording
    // =========================================================================

    @Test
    fun deleteRecording_removesTheRecording() = runTest {
        val recording = createRecording()
        repository.saveRecording(recording)

        // Confirm it exists
        assertNotNull(repository.getRecording(recording.id))

        repository.deleteRecording(recording.id)

        // Now it should be gone
        assertNull(repository.getRecording(recording.id))
    }

    // =========================================================================
    // 7. deleteRecording non-existent ID doesn't throw
    // =========================================================================

    @Test
    fun deleteRecording_nonExistentId_doesNotThrow() = runTest {
        // Should complete without throwing
        repository.deleteRecording("non-existent-id")
    }

    // =========================================================================
    // 8. saveTranscription + getTranscription round-trip
    // =========================================================================

    @Test
    fun saveTranscription_getTranscription_roundTrip() = runTest {
        val transcription = createTranscription()

        repository.saveTranscription(transcription)
        val retrieved = repository.getTranscription(transcription.recordingId)

        assertNotNull(retrieved)
        assertEquals(transcription.recordingId, retrieved.recordingId)
        assertEquals(transcription.text, retrieved.text)
        assertEquals(transcription.segments.size, retrieved.segments.size)
        assertEquals(transcription.segments[0].startMs, retrieved.segments[0].startMs)
        assertEquals(transcription.segments[0].endMs, retrieved.segments[0].endMs)
        assertEquals(transcription.segments[0].text, retrieved.segments[0].text)
        assertEquals(transcription.segments[0].speaker, retrieved.segments[0].speaker)
        assertEquals(transcription.segments[1].speaker, retrieved.segments[1].speaker) // null speaker
        assertEquals(transcription.language, retrieved.language)
        assertEquals(transcription.durationMs, retrieved.durationMs)
        assertEquals(transcription.diarizationUnavailable, retrieved.diarizationUnavailable)
    }

    // =========================================================================
    // 9. getTranscription returns null for non-existent
    // =========================================================================

    @Test
    fun getTranscription_nonExistentId_returnsNull() = runTest {
        val result = repository.getTranscription("no-such-recording")
        assertNull(result)
    }

    // =========================================================================
    // 10. saveSummary + getSummary round-trip
    // =========================================================================

    @Test
    fun saveSummary_getSummary_roundTrip() = runTest {
        val summary = createSummary()

        repository.saveSummary(summary)
        val retrieved = repository.getSummary(summary.recordingId)

        assertNotNull(retrieved)
        assertEquals(summary.recordingId, retrieved.recordingId)
        assertEquals(summary.summary, retrieved.summary)
        assertEquals(summary.keyPoints, retrieved.keyPoints)
        assertEquals(summary.actionItems, retrieved.actionItems)
        assertEquals(summary.questions, retrieved.questions)
        assertEquals(summary.tags, retrieved.tags)
        assertEquals(summary.modelUsed, retrieved.modelUsed)
        assertEquals(summary.recommendedQuestions, retrieved.recommendedQuestions)
        assertNotNull(retrieved.qualityRating)
        assertEquals(summary.qualityRating!!.pacing, retrieved.qualityRating!!.pacing)
        assertEquals(summary.qualityRating!!.questions, retrieved.qualityRating!!.questions)
        assertEquals(summary.qualityRating!!.goalSetting, retrieved.qualityRating!!.goalSetting)
        assertEquals(summary.qualityRating!!.nextSteps, retrieved.qualityRating!!.nextSteps)
        assertEquals(summary.qualityRating!!.overall, retrieved.qualityRating!!.overall)
    }

    // =========================================================================
    // 11. getSummary returns null for non-existent
    // =========================================================================

    @Test
    fun getSummary_nonExistentId_returnsNull() = runTest {
        val result = repository.getSummary("no-such-recording")
        assertNull(result)
    }

    // =========================================================================
    // 12. Multiple recordings stored independently
    // =========================================================================

    @Test
    fun multipleRecordings_storedIndependently() = runTest {
        val rec1 = createRecording(id = "rec-A", title = "Alpha Meeting")
        val rec2 = createRecording(id = "rec-B", title = "Beta Meeting")

        repository.saveRecording(rec1)
        repository.saveRecording(rec2)

        val retrievedA = repository.getRecording("rec-A")
        val retrievedB = repository.getRecording("rec-B")

        assertNotNull(retrievedA)
        assertNotNull(retrievedB)
        assertEquals("Alpha Meeting", retrievedA.title)
        assertEquals("Beta Meeting", retrievedB.title)

        // Updating one doesn't affect the other
        repository.updateRecording(rec1.copy(title = "Alpha Updated"))
        val afterUpdate = repository.getRecording("rec-B")
        assertNotNull(afterUpdate)
        assertEquals("Beta Meeting", afterUpdate.title)

        // Deleting one doesn't affect the other
        repository.deleteRecording("rec-A")
        assertNull(repository.getRecording("rec-A"))
        assertNotNull(repository.getRecording("rec-B"))
    }

    // =========================================================================
    // 13. All domain model fields preserved (complex nested types)
    // =========================================================================

    @Test
    fun complexNestedTypes_attachmentsAndQualityRating_preserved() = runTest {
        val recording = createRecording(
            attachments = listOf(
                Attachment(id = "att-1", filePath = "/img/screen1.png", timestampMs = 30000L, caption = "Diagram A"),
                Attachment(id = "att-2", filePath = "/img/screen2.png", timestampMs = 90000L, caption = "")
            ),
            highlights = listOf(0L, 500L, 999999L, Long.MAX_VALUE)
        )

        repository.saveRecording(recording)
        val retrieved = repository.getRecording(recording.id)

        assertNotNull(retrieved)
        assertEquals(2, retrieved.attachments.size)
        assertEquals("att-1", retrieved.attachments[0].id)
        assertEquals("/img/screen1.png", retrieved.attachments[0].filePath)
        assertEquals(30000L, retrieved.attachments[0].timestampMs)
        assertEquals("Diagram A", retrieved.attachments[0].caption)
        assertEquals("att-2", retrieved.attachments[1].id)
        assertEquals("", retrieved.attachments[1].caption)
        assertEquals(listOf(0L, 500L, 999999L, Long.MAX_VALUE), retrieved.highlights)
    }

    @Test
    fun summaryWithNullQualityRating_preserved() = runTest {
        val summary = SummaryResult(
            recordingId = "rec-null-qr",
            summary = "Short meeting",
            keyPoints = emptyList(),
            actionItems = emptyList(),
            questions = emptyList(),
            tags = emptyList(),
            modelUsed = "qwen3:8b",
            recommendedQuestions = emptyList(),
            qualityRating = null
        )

        repository.saveSummary(summary)
        val retrieved = repository.getSummary(summary.recordingId)

        assertNotNull(retrieved)
        assertNull(retrieved.qualityRating)
        assertEquals("Short meeting", retrieved.summary)
    }

    @Test
    fun recordingWithAllMeetingTemplates_preserved() = runTest {
        MeetingTemplate.entries.forEach { template ->
            val recording = createRecording(id = "rec-${template.name}", template = template)
            repository.saveRecording(recording)
            val retrieved = repository.getRecording(recording.id)
            assertNotNull(retrieved, "Should retrieve recording with template $template")
            assertEquals(template, retrieved.template, "Template $template should be preserved")
        }
    }

    @Test
    fun transcriptionWithEmptySegments_preserved() = runTest {
        val transcription = TranscriptionResult(
            recordingId = "rec-empty-seg",
            text = "Some text",
            segments = emptyList(),
            language = "fr",
            durationMs = 1000L,
            diarizationUnavailable = true
        )

        repository.saveTranscription(transcription)
        val retrieved = repository.getTranscription(transcription.recordingId)

        assertNotNull(retrieved)
        assertEquals("Some text", retrieved.text)
        assertEquals(emptyList(), retrieved.segments)
        assertEquals("fr", retrieved.language)
        assertEquals(1000L, retrieved.durationMs)
        assertTrue(retrieved.diarizationUnavailable)
    }
}
