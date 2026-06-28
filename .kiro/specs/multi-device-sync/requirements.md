# Requirements Document

## Introduction

Multi-Device Recording Synchronization enables Jeeves recordings, transcriptions, and summaries to synchronize across multiple devices (phone, work machine, MacBook) using CouchDB's built-in replication protocol. Each device maintains a local database for offline-first operation and syncs bidirectionally with a central CouchDB instance. Audio files (WAV) are handled separately from metadata due to their size, using CouchDB attachments with selective replication. The feature includes conflict resolution for concurrent edits (e.g., notes edited on two devices simultaneously) and provides users with visibility into sync status.

## Glossary

- **Sync_Engine**: The component responsible for managing bidirectional replication between the Local_Database and the Remote_Database using CouchDB's replication protocol
- **Local_Database**: A PouchDB-compatible or CouchDB-compatible local datastore on each device that holds recordings metadata, transcriptions, and summaries
- **Remote_Database**: The central CouchDB instance that acts as the synchronization hub between all devices
- **Sync_Status**: The current state of replication (idle, syncing, error, offline) displayed to the user
- **Document_Revision**: CouchDB's revision identifier (_rev) used for optimistic concurrency control and conflict detection
- **Conflict_Resolver**: The component that detects and resolves conflicting revisions of the same document when concurrent edits occur on different devices
- **Audio_Attachment**: A WAV audio file stored as a CouchDB attachment on the recording document, replicated separately from metadata
- **Selective_Replication**: A replication strategy that syncs metadata immediately but defers large Audio_Attachments until explicitly requested or connectivity allows
- **Device_Identifier**: A unique identifier assigned to each Jeeves installation to distinguish edits originating from different devices
- **Sync_Configuration**: User-provided settings including the Remote_Database URL, authentication credentials, and sync preferences

## Requirements

### Requirement 1: Sync Configuration

**User Story:** As a user, I want to configure my CouchDB sync endpoint, so that my devices can connect to a shared remote database.

#### Acceptance Criteria

1. THE Settings_Screen SHALL display a Sync section with input fields for Remote_Database URL (maximum 2048 characters), username (maximum 256 characters), and password (maximum 256 characters), where the password field masks input
2. WHEN a user enters Sync_Configuration credentials and saves, THE Settings_Repository SHALL persist the credentials such that the password is not stored in plaintext and is not readable from the settings file without decryption
3. WHEN a user presses "Test Connection", THE Sync_Engine SHALL attempt to connect and authenticate to the Remote_Database and report the result within 10 seconds, where success means the database is reachable and the credentials are accepted
4. IF the Remote_Database URL is malformed, THEN THE Sync_Engine SHALL display an error message indicating the URL format is invalid without attempting a network connection
5. IF the Remote_Database is unreachable or the credentials are rejected, THEN THE Sync_Engine SHALL display an error message that distinguishes between a network connectivity failure and an authentication failure
6. THE Settings_Screen SHALL allow the user to enable or disable synchronization independently of having a valid configuration stored
7. THE Sync_Engine SHALL generate and persist a unique Device_Identifier as a UUID on first launch that remains stable across application restarts
8. IF any of the three Sync_Configuration fields (URL, username, password) are empty when the user presses "Test Connection", THEN THE Settings_Screen SHALL indicate which fields are missing and not initiate a connection attempt

### Requirement 2: Local Database Layer

**User Story:** As a user, I want my recordings stored in a sync-compatible local database, so that they can participate in CouchDB replication without data loss.

#### Acceptance Criteria

1. THE Local_Database SHALL store Recording, TranscriptionResult, and SummaryResult documents in a CouchDB-compatible JSON format with _id and _rev fields
2. THE Local_Database SHALL implement the RecordingsRepository interface, maintaining backwards compatibility with existing application code
3. WHEN the application starts and sync is enabled and no prior migration record exists in the Local_Database, THE Local_Database SHALL migrate all existing SQLite Recording, TranscriptionResult, and SummaryResult rows into CouchDB-compatible documents and record a migration-complete marker to prevent re-execution on subsequent starts
4. THE Local_Database SHALL assign each document a deterministic _id derived from the document type and recording identifier (e.g., "recording:{id}", "transcription:{id}", "summary:{id}")
5. THE Local_Database SHALL preserve all fields from the existing Recording, TranscriptionResult, and SummaryResult data models during migration such that reading a migrated document back through the RecordingsRepository interface returns values equal to the original SQLite row for every field
6. WHEN a document is saved locally, THE Local_Database SHALL assign a new _rev value that is unique and distinct from the document's previous _rev value
7. IF the migration fails for one or more documents, THEN THE Local_Database SHALL skip the failed documents, log an error indicating which document identifiers failed and the reason, and continue migrating the remaining documents without rolling back successfully migrated documents

### Requirement 3: Bidirectional Replication

**User Story:** As a user, I want my recordings to sync automatically between my devices, so that I can access any meeting from any machine without manual file transfer.

#### Acceptance Criteria

1. WHEN synchronization is enabled and network connectivity is available, THE Sync_Engine SHALL establish and maintain a continuous bidirectional replication session with the Remote_Database, reconnecting automatically within 10 seconds if the session drops while connectivity remains available
2. WHEN a document is created or updated locally, THE Sync_Engine SHALL replicate the change to the Remote_Database within 30 seconds, measured from the time the local write completes to the time the Remote_Database acknowledges receipt, given network latency below 500ms and available bandwidth above 1 Mbps
3. WHEN a document is created or updated on the Remote_Database, THE Sync_Engine SHALL replicate the change to the Local_Database within 30 seconds, measured from the time the Remote_Database records the change to the time the Local_Database write completes, given network latency below 500ms and available bandwidth above 1 Mbps
4. WHEN network connectivity is lost, THE Sync_Engine SHALL queue all local changes in the order they occurred and preserve the queue persistently so that no queued changes are lost if the application is terminated while offline
5. WHEN network connectivity is restored after an outage, THE Sync_Engine SHALL replicate all queued local changes to the Remote_Database and pull all remote changes made during the offline period, completing reconciliation without user intervention
6. THE Sync_Engine SHALL replicate document deletions bidirectionally within the same time bounds as document creates and updates, so that a recording deleted on one device is removed from all synced devices
7. IF replication of an individual document fails due to a network error or server rejection, THEN THE Sync_Engine SHALL retry the failed document replication up to 3 times with exponential backoff starting at 5 seconds, and if all retries fail, SHALL mark the document with a sync error status visible in the Sync_Status indicator without blocking replication of other documents

### Requirement 4: Audio File Synchronization

**User Story:** As a user, I want my audio recordings available on other devices, so that I can re-listen or re-process recordings from any machine.

#### Acceptance Criteria

1. WHEN a recording is completed, THE Sync_Engine SHALL store the WAV audio file as a CouchDB attachment on the corresponding recording document
2. THE Sync_Engine SHALL use Selective_Replication to sync metadata immediately while deferring Audio_Attachment downloads until the user requests playback or explicitly triggers a full sync
3. WHEN a user opens a recording on a device that has not yet downloaded the Audio_Attachment, THE application SHALL display a download prompt with the file size and estimated download time
4. WHEN the user confirms the Audio_Attachment download, THE Sync_Engine SHALL fetch the attachment from the Remote_Database and store it locally
5. THE Settings_Screen SHALL provide an option to configure automatic Audio_Attachment download behaviour: "Always download", "Download on Wi-Fi only", or "Download on demand"
6. IF an Audio_Attachment download fails mid-transfer, THEN THE Sync_Engine SHALL retry the download up to 3 times with exponential backoff before reporting failure to the user

### Requirement 5: Conflict Detection and Resolution

**User Story:** As a user, I want concurrent edits from different devices to be merged without losing data, so that I can edit notes on multiple machines without coordination.

#### Acceptance Criteria

1. WHEN the same document is modified on two or more devices before replication completes, THE Conflict_Resolver SHALL automatically detect and resolve the conflicting revisions during replication without requiring user intervention
2. WHEN a conflict is detected on a Recording document's title or description fields, THE Conflict_Resolver SHALL apply a last-write-wins strategy based on the most recent modification timestamp with millisecond precision, and IF timestamps are identical, THEN THE Conflict_Resolver SHALL select the revision from the device whose Device_Identifier is lexicographically greater
3. WHEN a conflict is detected on a Recording document's postRecordingNote field, THE Conflict_Resolver SHALL concatenate all conflicting versions ordered by modification timestamp (oldest first), with each version prefixed by the originating Device_Identifier, preserving content from all devices
4. WHEN a conflict is detected on tags or highlights list fields, THE Conflict_Resolver SHALL merge the lists by taking the union of all values, treating duplicates as case-sensitive exact string matches, and removing duplicates
5. WHEN a conflict is detected on a TranscriptionResult or SummaryResult document, THE Conflict_Resolver SHALL retain the version with the longer text content (measured in character count) as the primary and store the other as a recoverable revision, and IF text lengths are equal, THEN THE Conflict_Resolver SHALL select the version with the more recent modification timestamp as the primary
6. THE Conflict_Resolver SHALL record all resolved conflicts in a local audit log including the Document _id, the Device_Identifier of each conflicting revision, the resolution strategy applied, and a timestamp of resolution
7. THE Conflict_Resolver SHALL retain audit log entries for a minimum of 90 days before they are eligible for deletion

### Requirement 6: Sync Status and Monitoring

**User Story:** As a user, I want to see the synchronization status, so that I know whether my data is up to date across devices.

#### Acceptance Criteria

1. THE application SHALL display a Sync_Status indicator in the main UI showing the current state: idle (synced), syncing (in progress), error (failed), or offline (no connectivity)
2. WHEN a sync error occurs, THE Sync_Status indicator SHALL display a tooltip or expandable message of no more than 200 characters indicating the nature of the failure
3. WHEN synchronization is actively transferring documents, THE Sync_Status indicator SHALL show the number of pending changes
4. THE application SHALL display the timestamp of the last successful sync completion in the Settings sync section, formatted as a relative duration (e.g., "5 minutes ago") when less than 24 hours, and as an absolute date and time when 24 hours or older
5. WHEN the user clicks the Sync_Status indicator while the current state is idle, error, or offline, THE application SHALL offer a "Sync Now" action to trigger an immediate replication cycle
6. IF synchronization has not completed successfully for more than 1 hour while the application is running, THEN THE application SHALL display a persistent warning notification that remains visible until the user dismisses it or a sync completes successfully
7. WHEN a sync operation completes successfully after the Sync_Status indicator was in the error state, THE Sync_Status indicator SHALL transition to the idle state and clear any previously displayed error message within 5 seconds
8. IF the user triggers "Sync Now" while a sync operation is already in progress, THEN THE application SHALL keep the current sync operation running and indicate to the user that synchronization is already in progress

### Requirement 7: Data Integrity and Security

**User Story:** As a user, I want my synced data to be transmitted securely and to remain consistent across devices, so that I can trust the system with sensitive meeting content.

#### Acceptance Criteria

1. THE Sync_Engine SHALL communicate with the Remote_Database exclusively over HTTPS (TLS 1.2 or higher)
2. THE Sync_Engine SHALL authenticate with the Remote_Database using the configured credentials on every replication session
3. IF TLS certificate validation fails, THEN THE Sync_Engine SHALL refuse the connection and report the certificate error to the user via the Sync_Status indicator
4. IF authentication with the Remote_Database fails, THEN THE Sync_Engine SHALL refuse the replication session, report an authentication error to the user via the Sync_Status indicator, and not retry automatically until the user updates credentials or explicitly triggers a new sync attempt
5. WHEN a document is received from replication, THE Local_Database SHALL validate the document structure against the known schemas for Recording, TranscriptionResult, and SummaryResult document types, rejecting any document that is missing required fields or contains fields of incorrect type
6. IF a replicated document fails schema validation, THEN THE Local_Database SHALL discard the malformed document, log the rejection with the document _id and reason, and continue processing remaining documents without interrupting the replication session
7. WHEN a document passes validation after replication, THE Local_Database SHALL update the local copy atomically to prevent partial writes
8. THE Settings_Repository SHALL NOT log or display sync credentials after initial configuration, except that the username and Remote_Database URL may be displayed in the Settings_Screen with the password masked as a fixed-length series of asterisks
