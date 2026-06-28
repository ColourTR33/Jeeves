# Implementation Plan: Multi-Device Recording Synchronization

## Overview

Implement bidirectional CouchDB replication for Jeeves recordings, transcriptions, and summaries across multiple devices. The implementation builds up from the document model and local store, through the replication engine, conflict resolution, and audio attachment handling, to the UI layer and data migration. Each step is incremental and testable.

## Tasks

- [x] 1. CouchDB document model and serialization
  - [x] 1.1 Create CouchDocument data classes and serialization
    - Create `shared/src/commonMain/kotlin/com/jeeves/shared/sync/DocumentSchema.kt`
    - Implement `CouchDocument`, `AttachmentStub`, `DocumentChange`, `ChangesResponse`, and `BulkDocResult` data classes with `@Serializable` annotations and `@SerialName` mappings for CouchDB fields (`_id`, `_rev`, `_deleted`, `_attachments`)
    - Implement `generateRev(generation, content)` function using MD5 hex digest
    - Implement `ConflictAuditEntry` data class with all fields from the design
    - _Requirements: 2.1, 2.4_

  - [x] 1.2 Implement domain-to-CouchDocument converters
    - Create conversion functions: `Recording.toCouchDocument(deviceId)`, `TranscriptionResult.toCouchDocument(deviceId)`, `SummaryResult.toCouchDocument(deviceId)`
    - Create reverse converters: `CouchDocument.toRecording()`, `CouchDocument.toTranscription()`, `CouchDocument.toSummary()`
    - Document ID convention: `"recording:{id}"`, `"transcription:{id}"`, `"summary:{id}"`
    - Ensure `modifiedAt` timestamp is set on every conversion
    - _Requirements: 2.1, 2.4, 2.5_

  - [x] 1.3 Write property test for document ID determinism
    - **Property 1: Document ID determinism**
    - Generate arbitrary Recording, TranscriptionResult, and SummaryResult objects
    - Assert that converting to CouchDocument always produces the same `_id` for the same recording identifier
    - **Validates: Requirements 2.4**

  - [x] 1.4 Write property test for serialization round-trip
    - **Property 9: Serialization round-trip**
    - Generate arbitrary domain objects, serialize to CouchDocument JSON, deserialize back
    - Assert equivalence of all fields
    - **Validates: Requirements 2.1, 2.2**

- [x] 2. LocalDocumentStore implementation
  - [x] 2.1 Implement LocalDocumentStore core operations
    - Create `shared/src/commonMain/kotlin/com/jeeves/shared/sync/LocalDocumentStore.kt`
    - Implement `get(id)`, `put(doc)`, `delete(id, rev)` with atomic file writes (write to `.tmp`, rename on success)
    - Implement `_rev` generation on each `put()` call with incrementing generation counter
    - Store documents as individual JSON files in a directory structure organized by type prefix
    - Emit `DocumentChange` events via `SharedFlow` on every write
    - _Requirements: 2.1, 2.6, 7.7_

  - [x] 2.2 Implement LocalDocumentStore bulk and query operations
    - Implement `bulkDocs(docs, newEdits)` for replication batch writes
    - Implement `changesSince(since, limit)` returning changes after a sequence number
    - Implement `allDocs(prefix)` for querying documents by type prefix
    - Maintain a persistent sequence counter incremented on each write
    - _Requirements: 2.1, 3.4_

  - [x] 2.3 Implement schema validation on document writes
    - Add validation in `put()` and `bulkDocs()` for documents received from replication (`newEdits = false`)
    - Validate required fields and field types for Recording, TranscriptionResult, and SummaryResult schemas
    - Reject malformed documents: discard, log `_id` and reason, continue processing
    - _Requirements: 7.5, 7.6_

  - [x] 2.4 Write property test for revision uniqueness
    - **Property 3: Revision uniqueness on write**
    - For any document, call `put()` multiple times and assert all generated `_rev` values are distinct
    - **Validates: Requirements 2.6**

  - [x] 2.5 Write property test for schema validation rejection
    - **Property 8: Schema validation rejects malformed documents**
    - Generate JSON objects with missing required fields or incorrect types
    - Assert they are rejected by `bulkDocs(newEdits = false)` and not persisted
    - **Validates: Requirements 7.5**

- [x] 3. CouchDbRecordingsRepository
  - [x] 3.1 Implement CouchDbRecordingsRepository
    - Create `desktopApp/src/desktopMain/kotlin/com/jeeves/desktop/data/CouchDbRecordingsRepository.kt`
    - Implement all `RecordingsRepository` interface methods by converting between domain models and CouchDB documents via `LocalDocumentStore`
    - Use the domain-to-CouchDocument converters from task 1.2
    - Ensure `getRecordings()` queries by `"recording:"` prefix, `getTranscription()` by `"transcription:{id}"`, etc.
    - _Requirements: 2.2, 2.4_

  - [x] 3.2 Write unit tests for CouchDbRecordingsRepository
    - Test all RecordingsRepository methods with an in-memory LocalDocumentStore
    - Verify CRUD operations produce correct CouchDB documents
    - Test that domain model fields are preserved through save/read cycles
    - _Requirements: 2.2, 2.5_

- [x] 4. Checkpoint - Core data layer
  - Ensure all tests pass, ask the user if questions arise.

- [x] 5. SyncEngine and CouchDbReplicator
  - [x] 5.1 Implement SyncConfiguration and connection validation
    - Create `shared/src/commonMain/kotlin/com/jeeves/shared/sync/SyncConfiguration.kt`
    - Implement `SyncConfiguration` data class with URL validation (max 2048 chars), username (max 256), encrypted password (max 256)
    - Implement URL format validation (must be valid HTTPS URL)
    - Implement empty-field checks returning which fields are missing
    - _Requirements: 1.1, 1.4, 1.8_

  - [x] 5.2 Implement SyncStatus and status flow
    - Create `shared/src/commonMain/kotlin/com/jeeves/shared/sync/SyncStatus.kt`
    - Implement `SyncStatus` sealed class: `Idle`, `Syncing(direction)`, `Error(message, retryable)`, `Offline`
    - Implement `ConnectionTestResult` and `ConnectionErrorType` enum
    - _Requirements: 6.1, 6.2_

  - [x] 5.3 Implement CouchDbReplicator HTTP protocol
    - Create `shared/src/commonMain/kotlin/com/jeeves/shared/sync/CouchDbReplicator.kt`
    - Implement `pushChanges()` using Ktor POST to `/_bulk_docs` endpoint
    - Implement `pullChanges()` using Ktor GET to `/_changes?since=seq&feed=longpoll`
    - Implement HTTPS-only enforcement and TLS certificate validation
    - Implement HTTP Basic Auth with credentials from SyncConfiguration
    - Implement retry logic: 3 retries with exponential backoff (5s, 10s, 20s) for 5xx errors
    - _Requirements: 3.2, 3.3, 3.7, 7.1, 7.2, 7.3_

  - [x] 5.4 Implement SyncEngine orchestrator
    - Create `shared/src/commonMain/kotlin/com/jeeves/shared/sync/SyncEngine.kt`
    - Implement `DefaultSyncEngine` class with `start()`, `stop()`, `syncNow()`, `testConnection()`
    - Implement continuous bidirectional replication using coroutines: push local changes, pull remote changes in a loop
    - Implement `StateFlow<SyncStatus>`, `StateFlow<Int>` for pending changes, `StateFlow<Long?>` for last sync timestamp
    - Implement auto-reconnect within 10 seconds on session drop
    - Implement network connectivity detection and offline queueing
    - Generate and persist `deviceId` UUID on first launch
    - _Requirements: 1.3, 1.5, 1.7, 3.1, 3.4, 3.5, 3.6, 6.3, 6.5, 6.7, 6.8, 7.4_

  - [x] 5.5 Write unit tests for SyncEngine and CouchDbReplicator
    - Use `ktor-client-mock` to test HTTP interactions
    - Test connection test scenarios: success, timeout, auth failure, TLS error, network unreachable
    - Test push/pull replication flows
    - Test retry logic with exponential backoff
    - Test status transitions: idle → syncing → idle, idle → error
    - Test offline queueing and reconnect replay
    - _Requirements: 1.3, 1.5, 3.1, 3.7, 7.1, 7.3_

- [x] 6. ConflictResolver
  - [x] 6.1 Implement ConflictResolver with field-specific strategies
    - Create `shared/src/commonMain/kotlin/com/jeeves/shared/sync/ConflictResolver.kt`
    - Implement `resolveRecording()`: last-write-wins for title/description (by `modifiedAt`, tiebreak by lexicographic `deviceId`); concatenation for `postRecordingNote` (ordered by timestamp, prefixed by deviceId); union merge for tags and highlights
    - Implement `resolveTextDocument()`: longest-content-wins for TranscriptionResult/SummaryResult (by character count, tiebreak by `modifiedAt`)
    - Implement audit log entry creation for every resolved conflict
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6_

  - [x] 6.2 Implement conflict audit log with 90-day retention
    - Store `ConflictAuditEntry` records in a dedicated file in the local store
    - Implement retention: entries older than 90 days are eligible for deletion on startup
    - _Requirements: 5.6, 5.7_

  - [x] 6.3 Write property test for last-write-wins correctness
    - **Property 4: Last-write-wins correctness for title/description**
    - Generate pairs of conflicting RecordingDocuments with distinct timestamps
    - Assert the winner has the greater `modifiedAt`; on tie, greater `deviceId` wins
    - **Validates: Requirements 5.2**

  - [x] 6.4 Write property test for note concatenation
    - **Property 5: Note concatenation preserves all content**
    - Generate pairs of conflicting recordings with non-empty `postRecordingNote`
    - Assert resolved note contains full text of both, ordered by `modifiedAt`, prefixed by `deviceId`
    - **Validates: Requirements 5.3**

  - [x] 6.5 Write property test for tags/highlights union merge
    - **Property 6: Tags/highlights union merge**
    - Generate pairs of conflicting recordings with varying tag/highlight lists
    - Assert resolved lists equal the set union (case-sensitive for tags)
    - **Validates: Requirements 5.4**

  - [x] 6.6 Write property test for longest-content-wins
    - **Property 7: Longest-content-wins for text documents**
    - Generate pairs of conflicting TranscriptionResult/SummaryResult documents
    - Assert the version with longer text wins; on tie, more recent `modifiedAt` wins
    - **Validates: Requirements 5.5**

- [x] 7. Checkpoint - Sync engine and conflict resolution
  - Ensure all tests pass, ask the user if questions arise.

- [x] 8. AudioAttachmentManager
  - [x] 8.1 Implement AudioAttachmentManager
    - Create `shared/src/commonMain/kotlin/com/jeeves/shared/sync/AudioAttachmentManager.kt`
    - Implement `uploadAudio()`: read WAV file, PUT as CouchDB attachment with `Content-Type: audio/wav`
    - Implement `downloadAudio()`: GET attachment from remote, save to local audio directory
    - Implement `isAudioAvailableLocally()`: check local file existence
    - Implement `getRemoteAudioSize()`: HEAD request on attachment URL
    - Implement retry logic: 3 retries with exponential backoff for failed downloads
    - _Requirements: 4.1, 4.2, 4.4, 4.6_

  - [x] 8.2 Implement CouchDbReplicator attachment methods
    - Add `pushAttachment()` and `pullAttachment()` to CouchDbReplicator
    - `pushAttachment()`: PUT `/{db}/{docId}/{attachmentName}` with `?rev=` parameter
    - `pullAttachment()`: GET `/{db}/{docId}/{attachmentName}` returning ByteArray
    - _Requirements: 4.1, 4.4_

  - [x] 8.3 Write unit tests for AudioAttachmentManager
    - Test upload flow with mock HTTP client
    - Test download with retry on failure
    - Test `isAudioAvailableLocally()` returns correct state
    - Test `getRemoteAudioSize()` HEAD request
    - _Requirements: 4.1, 4.4, 4.6_

- [x] 9. Data migration from SQLite
  - [x] 9.1 Implement DataMigrator
    - Create `desktopApp/src/desktopMain/kotlin/com/jeeves/desktop/data/DataMigrator.kt`
    - Read all existing Recording, TranscriptionResult, and SummaryResult rows from the existing `SqliteRecordingsRepository`
    - Convert each to CouchDB documents using converters from task 1.2
    - Write to LocalDocumentStore via `bulkDocs()`
    - Record migration-complete marker via `markMigrationComplete()` to prevent re-execution
    - On individual document failure: skip, log error with document ID and reason, continue
    - _Requirements: 2.3, 2.5, 2.7_

  - [x] 9.2 Write property test for migration round-trip preservation
    - **Property 2: Migration round-trip preservation**
    - Generate arbitrary Recording, TranscriptionResult, SummaryResult objects
    - Simulate migration (convert to CouchDocument, write, read back through RecordingsRepository)
    - Assert all fields are equal to the original
    - **Validates: Requirements 2.5**

- [x] 10. Checkpoint - Backend complete
  - Ensure all tests pass, ask the user if questions arise.

- [x] 11. UI: Settings sync section
  - [x] 11.1 Add Sync configuration section to SettingsScreen
    - Modify `desktopApp/src/desktopMain/kotlin/com/jeeves/desktop/ui/screens/SettingsScreen.kt`
    - Add input fields: Remote Database URL, username, password (masked)
    - Add "Test Connection" button with result feedback (success, network error, auth error, invalid URL)
    - Add enable/disable sync toggle
    - Add audio download policy selector: "Always download", "Download on Wi-Fi only", "Download on demand"
    - Show last successful sync timestamp (relative for <24h, absolute for ≥24h)
    - Validate empty fields on "Test Connection" and indicate which are missing
    - _Requirements: 1.1, 1.2, 1.3, 1.4, 1.5, 1.6, 1.8, 4.5, 6.4_

  - [x] 11.2 Implement SyncStatusIndicator composable
    - Create `desktopApp/src/desktopMain/kotlin/com/jeeves/desktop/ui/components/SyncStatusIndicator.kt`
    - Display current sync state: idle (checkmark), syncing (animated), error (warning icon), offline (disconnected icon)
    - Show pending changes count during active sync
    - Show tooltip with error message (max 200 chars) on error state
    - Add "Sync Now" action on click when idle, error, or offline
    - Show "sync already in progress" feedback if clicked while syncing
    - _Requirements: 6.1, 6.2, 6.3, 6.5, 6.8_

  - [x] 11.3 Implement persistent sync warning notification
    - Add logic to show a persistent warning if sync hasn't completed for >1 hour while app is running
    - Warning dismisses on user action or successful sync completion
    - Integrate with existing `NotificationBanner` component
    - _Requirements: 6.6_

  - [x] 11.4 Add audio download prompt for remote recordings
    - When user opens a recording without local audio, show download prompt with file size and estimated time
    - On confirm, trigger `AudioAttachmentManager.downloadAudio()`
    - _Requirements: 4.3, 4.4_

- [x] 12. Credential encryption
  - [x] 12.1 Implement encrypted password storage
    - Integrate with system keychain or implement AES encryption for the sync password at rest
    - Ensure password is never stored in plaintext in settings files
    - Ensure password is never logged or displayed (only masked asterisks in UI)
    - Decrypt only when constructing HTTP auth headers
    - _Requirements: 1.2, 7.8_

- [x] 13. Wiring and integration
  - [x] 13.1 Wire SyncEngine into application lifecycle
    - Modify `desktopApp/src/desktopMain/kotlin/com/jeeves/desktop/ui/AppInitializer.kt`
    - On startup: check if sync is enabled, run migration if needed, start SyncEngine
    - On shutdown: stop SyncEngine gracefully
    - Swap `RecordingsRepository` implementation to `CouchDbRecordingsRepository` when sync is enabled
    - Connect `SyncStatusIndicator` to `SyncEngine.status` StateFlow
    - _Requirements: 2.3, 3.1, 1.6_

  - [x] 13.2 Write integration tests for end-to-end sync flow
    - Test full push/pull cycle with ktor-client-mock
    - Test conflict resolution end-to-end: two concurrent writes, pull, verify resolution
    - Test migration from existing repository data to CouchDB documents
    - Test SyncEngine lifecycle: start → sync → stop
    - _Requirements: 3.1, 3.2, 3.3, 5.1_

- [x] 14. Final checkpoint
  - Ensure all tests pass, ask the user if questions arise.

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- Property tests validate universal correctness properties from the design document
- Unit tests validate specific examples and edge cases
- The project uses Kotest for property-based testing (already in dependencies)
- Ktor mock client is available for HTTP-level unit tests
- All sync components live in `shared/src/commonMain/kotlin/com/jeeves/shared/sync/`
- Desktop-specific implementations (repository, migration, UI) live in `desktopApp/`

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1", "5.1", "5.2"] },
    { "id": 1, "tasks": ["1.2", "2.1"] },
    { "id": 2, "tasks": ["1.3", "1.4", "2.2", "2.3"] },
    { "id": 3, "tasks": ["2.4", "2.5", "3.1"] },
    { "id": 4, "tasks": ["3.2", "5.3", "6.1"] },
    { "id": 5, "tasks": ["5.4", "6.2", "6.3", "6.4", "6.5", "6.6"] },
    { "id": 6, "tasks": ["5.5", "8.1", "8.2"] },
    { "id": 7, "tasks": ["8.3", "9.1"] },
    { "id": 8, "tasks": ["9.2", "11.1", "12.1"] },
    { "id": 9, "tasks": ["11.2", "11.3", "11.4"] },
    { "id": 10, "tasks": ["13.1"] },
    { "id": 11, "tasks": ["13.2"] }
  ]
}
```
