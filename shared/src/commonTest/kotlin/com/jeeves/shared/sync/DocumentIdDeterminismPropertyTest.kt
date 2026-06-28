package com.jeeves.shared.sync

import com.jeeves.shared.domain.*
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Feature: multi-device-sync, Property 1: Document ID determinism

/**
 * Property-based tests for document ID determinism.
 *
 * For any Recording, TranscriptionResult, or SummaryResult domain object,
 * converting it to a CouchDB document and reading back the _id field SHALL
 * always produce the same deterministic value derived from the document type
 * and the recording identifier.
 *
 * **Validates: Requirements 2.4**
 */
@OptIn(io.kotest.common.ExperimentalKotest::class)
class DocumentIdDeterminismPropertyTest {

    private val config = PropTestConfig(iterations = 100)

    // =========================================================================
    // Generators
    // =========================================================================

    private val arbNonEmptyString: Arb<String> = Arb.string(1..50)
        .filter { it.isNotBlank() }

    private val arbDeviceId: Arb<String> = Arb.string(5..40)
        .filter { it.isNotBlank() }

    private val arbTimestamp: Arb<Long> = Arb.long(0L..9_999_999_999_999L)

    private val arbMeetingTemplate: Arb<MeetingTemplate> = Arb.enum<MeetingTemplate>()

    private val arbAttachment: Arb<Attachment> = Arb.bind(
        arbNonEmptyString,
        arbNonEmptyString,
        Arb.long(0L..Long.MAX_VALUE / 2),
        Arb.string(0..100)
    ) { id, filePath, timestampMs, caption ->
        Attachment(id, filePath, timestampMs, caption)
    }

    private val arbRecording: Arb<Recording> = Arb.bind(
        arbNonEmptyString,          // id
        arbNonEmptyString,          // filePath
        Arb.long(0L..86_400_000L), // durationMs
        arbTimestamp,               // createdAt
        Arb.string(0..100),        // title
        Arb.string(0..200),        // description
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
            arbMeetingTemplate,
            Arb.list(Arb.string(1..20), 0..5),
            Arb.string(0..50),
            Arb.list(Arb.long(0L..86_400_000L), 0..5),
            Arb.list(arbAttachment, 0..3),
            Arb.string(0..200)
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

    private val arbTranscriptionSegment: Arb<TranscriptionSegment> = Arb.bind(
        Arb.long(0L..100_000L),
        Arb.long(100_001L..200_000L),
        Arb.string(1..100),
        Arb.string(0..20).orNull()
    ) { startMs, endMs, text, speaker ->
        TranscriptionSegment(startMs, endMs, text, speaker)
    }

    private val arbTranscriptionResult: Arb<TranscriptionResult> = Arb.bind(
        arbNonEmptyString,                              // recordingId
        Arb.string(0..500),                            // text
        Arb.list(arbTranscriptionSegment, 0..5),       // segments
        Arb.of("en", "es", "fr", "de", "ja"),         // language
        Arb.long(0L..86_400_000L),                     // durationMs
        Arb.boolean()                                   // diarizationUnavailable
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

    private val arbQualityRating: Arb<QualityRating> = Arb.bind(
        Arb.int(1..5),
        Arb.int(1..5),
        Arb.int(1..5),
        Arb.int(1..5)
    ) { pacing, questions, goalSetting, nextSteps ->
        val overall = (pacing + questions + goalSetting + nextSteps) / 4.0
        QualityRating(pacing, questions, goalSetting, nextSteps, overall)
    }

    private val arbSummaryResult: Arb<SummaryResult> = Arb.bind(
        arbNonEmptyString,                          // recordingId
        Arb.string(0..500),                        // summary
        Arb.list(Arb.string(1..50), 0..5),        // keyPoints
        Arb.list(Arb.string(1..50), 0..5),        // actionItems
        Arb.list(Arb.string(1..50), 0..5),        // questions
        Arb.list(Arb.string(1..20), 0..5),        // tags
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
            Arb.string(1..30),                     // modelUsed
            Arb.list(Arb.string(1..80), 0..5),    // recommendedQuestions
            arbQualityRating.orNull()              // qualityRating
        ) { modelUsed, recommendedQuestions, qualityRating ->
            base.copy(
                modelUsed = modelUsed,
                recommendedQuestions = recommendedQuestions,
                qualityRating = qualityRating
            )
        }
    }

    // =========================================================================
    // Property Tests
    // =========================================================================

    /**
     * Property 1: Document ID determinism for Recording
     *
     * For any Recording, converting it to a CouchDocument always produces
     * the same _id ("recording:{id}") regardless of deviceId or modifiedAt.
     *
     * **Validates: Requirements 2.4**
     */
    @Test
    fun property1_recording_documentId_isDeterministic() = runTest {
        checkAll(config, arbRecording, arbDeviceId, arbTimestamp, arbDeviceId, arbTimestamp) {
            recording, deviceId1, ts1, deviceId2, ts2 ->

            val doc1 = recording.toCouchDocument(deviceId1, ts1)
            val doc2 = recording.toCouchDocument(deviceId2, ts2)

            assertEquals(
                doc1.id,
                doc2.id,
                "Recording _id must be deterministic regardless of deviceId/modifiedAt. " +
                    "Expected 'recording:${recording.id}' but got '${doc1.id}' and '${doc2.id}'"
            )
            assertEquals("recording:${recording.id}", doc1.id)
        }
    }

    /**
     * Property 1: Document ID determinism for TranscriptionResult
     *
     * For any TranscriptionResult, converting it to a CouchDocument always produces
     * the same _id ("transcription:{recordingId}") regardless of deviceId or modifiedAt.
     *
     * **Validates: Requirements 2.4**
     */
    @Test
    fun property1_transcription_documentId_isDeterministic() = runTest {
        checkAll(config, arbTranscriptionResult, arbDeviceId, arbTimestamp, arbDeviceId, arbTimestamp) {
            transcription, deviceId1, ts1, deviceId2, ts2 ->

            val doc1 = transcription.toCouchDocument(deviceId1, ts1)
            val doc2 = transcription.toCouchDocument(deviceId2, ts2)

            assertEquals(
                doc1.id,
                doc2.id,
                "TranscriptionResult _id must be deterministic regardless of deviceId/modifiedAt. " +
                    "Expected 'transcription:${transcription.recordingId}' but got '${doc1.id}' and '${doc2.id}'"
            )
            assertEquals("transcription:${transcription.recordingId}", doc1.id)
        }
    }

    /**
     * Property 1: Document ID determinism for SummaryResult
     *
     * For any SummaryResult, converting it to a CouchDocument always produces
     * the same _id ("summary:{recordingId}") regardless of deviceId or modifiedAt.
     *
     * **Validates: Requirements 2.4**
     */
    @Test
    fun property1_summary_documentId_isDeterministic() = runTest {
        checkAll(config, arbSummaryResult, arbDeviceId, arbTimestamp, arbDeviceId, arbTimestamp) {
            summary, deviceId1, ts1, deviceId2, ts2 ->

            val doc1 = summary.toCouchDocument(deviceId1, ts1)
            val doc2 = summary.toCouchDocument(deviceId2, ts2)

            assertEquals(
                doc1.id,
                doc2.id,
                "SummaryResult _id must be deterministic regardless of deviceId/modifiedAt. " +
                    "Expected 'summary:${summary.recordingId}' but got '${doc1.id}' and '${doc2.id}'"
            )
            assertEquals("summary:${summary.recordingId}", doc1.id)
        }
    }
}
