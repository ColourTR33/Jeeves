package com.jeeves.shared.sync

import com.jeeves.shared.domain.*
import io.kotest.property.Arb
import io.kotest.property.PropTestConfig
import io.kotest.property.arbitrary.*
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

// Feature: multi-device-sync, Property 9: Serialization round-trip

/**
 * Property-based test for serialization round-trip of domain objects through CouchDocument.
 *
 * For any valid Recording, TranscriptionResult, or SummaryResult domain object,
 * serializing to a CouchDB document JSON and deserializing back SHALL produce
 * an equivalent domain object.
 *
 * **Validates: Requirements 2.1, 2.2**
 */
class SerializationRoundTripPropertyTest {

    private val config = PropTestConfig(iterations = 100)

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
        Arb.long(0L..3600000L),    // endMs delta (will be added to start)
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
        Arb.string(1..30),                    // id
        Arb.string(1..50),                    // filePath
        Arb.long(0L..36000000L),              // durationMs
        Arb.long(1000000000000L..1800000000000L), // createdAt
        Arb.string(0..50),                    // title
        Arb.string(0..100),                   // description
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
            Arb.list(Arb.string(1..15), 0..5),    // tags
            Arb.string(0..20),                      // folder
            Arb.list(Arb.long(0L..36000000L), 0..5), // highlights
            Arb.list(arbAttachment, 0..3),          // attachments
            Arb.string(0..100)                      // postRecordingNote
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
        Arb.string(1..30),                     // recordingId
        Arb.string(0..200),                    // text
        Arb.list(arbTranscriptionSegment, 0..5), // segments
        Arb.of("en", "es", "fr", "de", "ja"), // language
        Arb.long(0L..36000000L),               // durationMs
        Arb.boolean()                          // diarizationUnavailable
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
    // Property 9: Serialization round-trip
    // =========================================================================

    @Test
    fun property9_recording_serializationRoundTrip_producesEquivalentObject() = runTest {
        checkAll(config, arbRecording, arbDeviceId, arbModifiedAt) { recording, deviceId, modifiedAt ->
            val doc = recording.toCouchDocument(deviceId, modifiedAt)
            val restored = doc.toRecording()

            assertEquals(
                recording,
                restored,
                "Recording round-trip failed for id=${recording.id}"
            )
        }
    }

    @Test
    fun property9_transcriptionResult_serializationRoundTrip_producesEquivalentObject() = runTest {
        checkAll(config, arbTranscriptionResult, arbDeviceId, arbModifiedAt) { transcription, deviceId, modifiedAt ->
            val doc = transcription.toCouchDocument(deviceId, modifiedAt)
            val restored = doc.toTranscription()

            assertEquals(
                transcription,
                restored,
                "TranscriptionResult round-trip failed for recordingId=${transcription.recordingId}"
            )
        }
    }

    @Test
    fun property9_summaryResult_serializationRoundTrip_producesEquivalentObject() = runTest {
        checkAll(config, arbSummaryResult, arbDeviceId, arbModifiedAt) { summary, deviceId, modifiedAt ->
            val doc = summary.toCouchDocument(deviceId, modifiedAt)
            val restored = doc.toSummary()

            assertEquals(
                summary,
                restored,
                "SummaryResult round-trip failed for recordingId=${summary.recordingId}"
            )
        }
    }
}
