package com.jeeves.desktop.data

import com.jeeves.shared.domain.*
import com.jeeves.shared.sync.LocalDocumentStore
import com.jeeves.shared.sync.toCouchDocument
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// Feature: multi-device-sync, Property 2: Migration round-trip preservation

/**
 * Property-based test for migration round-trip preservation.
 *
 *
 * For any valid Recording, TranscriptionResult, or SummaryResult, migrating it from
 * the SQLite representation to a CouchDB document and reading it back through the
 * RecordingsRepository interface SHALL produce a value equal to the original for every field.
 *
 * The test simulates the migration path:
 * 1. Convert domain object to CouchDocument via toCouchDocument()
 * 2. Write to LocalDocumentStore via bulkDocs(newEdits=true)
 * 3. Read back via CouchDbRecordingsRepository.getRecording/getTranscription/getSummary
 * 4. Assert equality with the original
 *
 * **Validates: Requirements 2.5**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class MigrationRoundTripPropertyTest {

    private val config = PropTestConfig(iterations = 100)

    private lateinit var tempDir: File
    private lateinit var store: LocalDocumentStore
    private lateinit var repository: CouchDbRecordingsRepository

    private val testDeviceId = "migration-test-device"

    @BeforeTest
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "migration-pbt-${System.nanoTime()}")
        tempDir.mkdirs()
        store = LocalDocumentStore(tempDir)
        repository = CouchDbRecordingsRepository(store, testDeviceId)
    }

    @AfterTest
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    // =========================================================================
    // Generators
    // =========================================================================

    private val arbAttachment: Arb<Attachment> = Arb.bind(
        Arb.string(1..20),         // id
        Arb.string(1..50),         // filePath
        Arb.long(0L..3600000L),    // timestampMs
        Arb.string(0..30)          // caption
    ) { id, filePath, timestampMs, caption ->
        Attachment(id = id, filePath = filePath, timestampMs = timestampMs, caption = caption)
    }

    private val arbTranscriptionSegment: Arb<TranscriptionSegment> = Arb.bind(
        Arb.long(0L..3600000L),    // startMs
        Arb.long(0L..3600000L),    // endMs delta (added to start)
        Arb.string(0..100),        // text
        Arb.string(0..20).orNull() // speaker
    ) { startMs, endDelta, text, speaker ->
        TranscriptionSegment(
            startMs = startMs,
            endMs = startMs + endDelta,
            text = text,
            speaker = speaker
        )
    }

    private val arbQualityRating: Arb<QualityRating> = Arb.bind(
        Arb.int(1..5),    // pacing
        Arb.int(1..5),    // questions
        Arb.int(1..5),    // goalSetting
        Arb.int(1..5),    // nextSteps
    ) { pacing, questions, goalSetting, nextSteps ->
        val overall = (pacing + questions + goalSetting + nextSteps) / 4.0
        val roundedOverall = (overall * 10).toLong() / 10.0 // 1 decimal place
        QualityRating(
            pacing = pacing,
            questions = questions,
            goalSetting = goalSetting,
            nextSteps = nextSteps,
            overall = roundedOverall
        )
    }

    private val arbRecording: Arb<Recording> = Arb.bind(
        Arb.string(1..30),                              // id
        Arb.string(1..50),                              // filePath
        Arb.long(0L..36000000L),                        // durationMs
        Arb.long(1000000000000L..1800000000000L),       // createdAt
        Arb.string(0..50),                              // title
        Arb.string(0..100),                             // description
    ) { id, filePath, durationMs, createdAt, title, description ->
        Recording(
            id = id,
            filePath = filePath,
            durationMs = durationMs,
            createdAt = createdAt,
            title = title,
            description = description
        )
    }.flatMap { base ->
        Arb.bind(
            Arb.enum<MeetingTemplate>(),
            Arb.list(Arb.string(1..15), 0..5),              // tags
            Arb.string(0..20),                               // folder
            Arb.list(Arb.long(0L..36000000L), 0..5),         // highlights
            Arb.list(arbAttachment, 0..3),                   // attachments
            Arb.string(0..100)                               // postRecordingNote
        ) { template, tags, folder, highlights, attachments, note ->
            base.copy(
                template = template,
                tags = tags,
                folder = folder,
                highlights = highlights,
                attachments = attachments,
                postRecordingNote = note
            )
        }
    }

    private val arbTranscriptionResult: Arb<TranscriptionResult> = Arb.bind(
        Arb.string(1..30),                         // recordingId
        Arb.string(0..200),                        // text
        Arb.list(arbTranscriptionSegment, 0..5),   // segments
        Arb.of("en", "es", "fr", "de", "ja"),     // language
        Arb.long(0L..36000000L),                   // durationMs
        Arb.boolean()                              // diarizationUnavailable
    ) { recordingId, text, segments, language, durationMs, diarizationUnavailable ->
        TranscriptionResult(
            recordingId = recordingId,
            text = text,
            segments = segments,
            language = language,
            durationMs = durationMs,
            diarizationUnavailable = diarizationUnavailable
        )
    }

    private val arbSummaryResult: Arb<SummaryResult> = Arb.bind(
        Arb.string(1..30),                     // recordingId
        Arb.string(0..200),                    // summary
        Arb.list(Arb.string(1..50), 0..5),     // keyPoints
        Arb.list(Arb.string(1..50), 0..5),     // actionItems
        Arb.list(Arb.string(1..50), 0..5),     // questions
        Arb.list(Arb.string(1..15), 0..5),     // tags
    ) { recordingId, summary, keyPoints, actionItems, questions, tags ->
        SummaryResult(
            recordingId = recordingId,
            summary = summary,
            keyPoints = keyPoints,
            actionItems = actionItems,
            questions = questions,
            tags = tags
        )
    }.flatMap { base ->
        Arb.bind(
            Arb.string(0..30),                     // modelUsed
            Arb.list(Arb.string(1..50), 0..5),     // recommendedQuestions
            arbQualityRating.orNull()              // qualityRating
        ) { modelUsed, recommendedQuestions, qualityRating ->
            base.copy(
                modelUsed = modelUsed,
                recommendedQuestions = recommendedQuestions,
                qualityRating = qualityRating
            )
        }
    }

    private val arbDeviceId: Arb<String> = Arb.string(5..30)
    private val arbModifiedAt: Arb<Long> = Arb.long(1000000000000L..1800000000000L)

    // =========================================================================
    // Property 2: Migration round-trip preservation
    // =========================================================================

    /**
     * Property 2 (Recording): Migrating a Recording to CouchDocument, writing to the
     * LocalDocumentStore, and reading back through CouchDbRecordingsRepository preserves
     * all fields.
     *
     * **Validates: Requirements 2.5**
     */
    @Test
    fun property2_recording_migrationRoundTrip_preservesAllFields() = runTest {
        checkAll(config, arbRecording, arbDeviceId, arbModifiedAt) { recording, deviceId, modifiedAt ->
            // Fresh store per iteration to avoid ID collisions
            val iterDir = File(tempDir, "rec-${System.nanoTime()}")
            iterDir.mkdirs()
            val iterStore = LocalDocumentStore(iterDir)
            val iterRepo = CouchDbRecordingsRepository(iterStore, deviceId)

            // Simulate migration: convert to CouchDocument and write via bulkDocs
            val doc = recording.toCouchDocument(deviceId, modifiedAt)
            val results = iterStore.bulkDocs(listOf(doc), newEdits = true)
            assert(results.all { it.ok }) {
                "bulkDocs failed for recording id=${recording.id}: ${results.map { it.error }}"
            }

            // Read back through the repository interface
            val restored = iterRepo.getRecording(recording.id)

            assertNotNull(restored, "Recording should be readable after migration for id=${recording.id}")
            assertEquals(
                recording,
                restored,
                "Migration round-trip failed for Recording id=${recording.id}"
            )

            // Cleanup
            iterDir.deleteRecursively()
        }
    }

    /**
     * Property 2 (TranscriptionResult): Migrating a TranscriptionResult to CouchDocument,
     * writing to the LocalDocumentStore, and reading back through CouchDbRecordingsRepository
     * preserves all fields.
     *
     * **Validates: Requirements 2.5**
     */
    @Test
    fun property2_transcriptionResult_migrationRoundTrip_preservesAllFields() = runTest {
        checkAll(config, arbTranscriptionResult, arbDeviceId, arbModifiedAt) { transcription, deviceId, modifiedAt ->
            // Fresh store per iteration to avoid ID collisions
            val iterDir = File(tempDir, "trans-${System.nanoTime()}")
            iterDir.mkdirs()
            val iterStore = LocalDocumentStore(iterDir)
            val iterRepo = CouchDbRecordingsRepository(iterStore, deviceId)

            // Simulate migration: convert to CouchDocument and write via bulkDocs
            val doc = transcription.toCouchDocument(deviceId, modifiedAt)
            val results = iterStore.bulkDocs(listOf(doc), newEdits = true)
            assert(results.all { it.ok }) {
                "bulkDocs failed for transcription recordingId=${transcription.recordingId}: ${results.map { it.error }}"
            }

            // Read back through the repository interface
            val restored = iterRepo.getTranscription(transcription.recordingId)

            assertNotNull(restored, "TranscriptionResult should be readable after migration for recordingId=${transcription.recordingId}")
            assertEquals(
                transcription,
                restored,
                "Migration round-trip failed for TranscriptionResult recordingId=${transcription.recordingId}"
            )

            // Cleanup
            iterDir.deleteRecursively()
        }
    }

    /**
     * Property 2 (SummaryResult): Migrating a SummaryResult to CouchDocument,
     * writing to the LocalDocumentStore, and reading back through CouchDbRecordingsRepository
     * preserves all fields.
     *
     * **Validates: Requirements 2.5**
     */
    @Test
    fun property2_summaryResult_migrationRoundTrip_preservesAllFields() = runTest {
        checkAll(config, arbSummaryResult, arbDeviceId, arbModifiedAt) { summary, deviceId, modifiedAt ->
            // Fresh store per iteration to avoid ID collisions
            val iterDir = File(tempDir, "sum-${System.nanoTime()}")
            iterDir.mkdirs()
            val iterStore = LocalDocumentStore(iterDir)
            val iterRepo = CouchDbRecordingsRepository(iterStore, deviceId)

            // Simulate migration: convert to CouchDocument and write via bulkDocs
            val doc = summary.toCouchDocument(deviceId, modifiedAt)
            val results = iterStore.bulkDocs(listOf(doc), newEdits = true)
            assert(results.all { it.ok }) {
                "bulkDocs failed for summary recordingId=${summary.recordingId}: ${results.map { it.error }}"
            }

            // Read back through the repository interface
            val restored = iterRepo.getSummary(summary.recordingId)

            assertNotNull(restored, "SummaryResult should be readable after migration for recordingId=${summary.recordingId}")
            assertEquals(
                summary,
                restored,
                "Migration round-trip failed for SummaryResult recordingId=${summary.recordingId}"
            )

            // Cleanup
            iterDir.deleteRecursively()
        }
    }
}
