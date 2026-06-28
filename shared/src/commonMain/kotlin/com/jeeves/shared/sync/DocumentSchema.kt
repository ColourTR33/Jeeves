package com.jeeves.shared.sync

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * CouchDB-compatible document wrapper used for local storage and replication.
 * All documents stored locally and replicated use this envelope.
 */
@Serializable
data class CouchDocument(
    @SerialName("_id") val id: String,
    @SerialName("_rev") val rev: String? = null,
    @SerialName("_deleted") val deleted: Boolean = false,
    @SerialName("_attachments") val attachments: Map<String, AttachmentStub>? = null,
    val type: String,
    val deviceId: String,
    val modifiedAt: Long,
    val body: JsonObject
)

/**
 * CouchDB attachment stub metadata. When stub=true, the attachment content
 * is not inline and must be fetched separately.
 */
@Serializable
data class AttachmentStub(
    @SerialName("content_type") val contentType: String,
    val length: Long,
    val digest: String,
    val stub: Boolean = true
)

/**
 * Represents a single document change event from the local store or CouchDB _changes feed.
 */
@Serializable
data class DocumentChange(
    val id: String,
    val rev: String,
    val deleted: Boolean = false
)

/**
 * Response from a CouchDB _changes feed query or local changesSince() call.
 */
@Serializable
data class ChangesResponse(
    val results: List<DocumentChange>,
    @SerialName("last_seq") val lastSeq: String
)

/**
 * Per-document result from a CouchDB _bulk_docs operation.
 */
@Serializable
data class BulkDocResult(
    val id: String,
    val rev: String? = null,
    val ok: Boolean = false,
    val error: String? = null,
    val reason: String? = null
)

/**
 * Strategy used to resolve a conflict between two document revisions.
 */
@Serializable
enum class ResolutionStrategy {
    LAST_WRITE_WINS,
    CONCATENATE,
    UNION_MERGE,
    LONGEST_CONTENT
}

/**
 * Audit log entry recorded every time a conflict is resolved.
 * Retained for a minimum of 90 days.
 */
@Serializable
data class ConflictAuditEntry(
    val id: String,
    val documentId: String,
    val localDeviceId: String,
    val remoteDeviceId: String,
    val strategy: ResolutionStrategy,
    val resolvedAt: Long,
    val localRev: String,
    val remoteRev: String,
    val winnerRev: String
)

/**
 * Generates a CouchDB-style revision string: "{generation}-{md5hex}".
 *
 * @param generation The revision generation number (increments on each write).
 * @param content The document content to hash.
 * @return A revision string in the format "N-hexdigest".
 */
fun generateRev(generation: Int, content: String): String {
    val hash = md5Hex(content.encodeToByteArray())
    return "$generation-$hash"
}

/**
 * Computes the MD5 hex digest of the given byte array.
 * Pure Kotlin implementation for Kotlin Multiplatform compatibility.
 */
@OptIn(ExperimentalUnsignedTypes::class)
internal fun md5Hex(input: ByteArray): String {
    // MD5 works on 512-bit (64-byte) blocks
    // Step 1: Pre-processing - padding
    val messageLenBits = input.size.toLong() * 8
    // Append 0x80 byte, then zeros until length ≡ 56 (mod 64), then 8-byte length
    val padded = run {
        val extra = (56 - (input.size + 1).mod(64)).mod(64)
        val totalLen = input.size + 1 + extra + 8
        val result = ByteArray(totalLen)
        input.copyInto(result)
        result[input.size] = 0x80.toByte()
        // Append original length in bits as 64-bit little-endian
        for (i in 0..7) {
            result[totalLen - 8 + i] = ((messageLenBits ushr (i * 8)) and 0xFF).toByte()
        }
        result
    }

    // Step 2: Initialize MD5 state
    var a0 = 0x67452301u
    var b0 = 0xEFCDAB89u
    var c0 = 0x98BADCFEu
    var d0 = 0x10325476u

    // Per-round shift amounts
    val s = intArrayOf(
        7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22, 7, 12, 17, 22,
        5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20, 5, 9, 14, 20,
        4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23, 4, 11, 16, 23,
        6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21, 6, 10, 15, 21
    )

    // Pre-computed table of T[i] = floor(2^32 * abs(sin(i + 1)))
    val k = uintArrayOf(
        0xd76aa478u, 0xe8c7b756u, 0x242070dbu, 0xc1bdceeeu,
        0xf57c0fafu, 0x4787c62au, 0xa8304613u, 0xfd469501u,
        0x698098d8u, 0x8b44f7afu, 0xffff5bb1u, 0x895cd7beu,
        0x6b901122u, 0xfd987193u, 0xa679438eu, 0x49b40821u,
        0xf61e2562u, 0xc040b340u, 0x265e5a51u, 0xe9b6c7aau,
        0xd62f105du, 0x02441453u, 0xd8a1e681u, 0xe7d3fbc8u,
        0x21e1cde6u, 0xc33707d6u, 0xf4d50d87u, 0x455a14edu,
        0xa9e3e905u, 0xfcefa3f8u, 0x676f02d9u, 0x8d2a4c8au,
        0xfffa3942u, 0x8771f681u, 0x6d9d6122u, 0xfde5380cu,
        0xa4beea44u, 0x4bdecfa9u, 0xf6bb4b60u, 0xbebfbc70u,
        0x289b7ec6u, 0xeaa127fau, 0xd4ef3085u, 0x04881d05u,
        0xd9d4d039u, 0xe6db99e5u, 0x1fa27cf8u, 0xc4ac5665u,
        0xf4292244u, 0x432aff97u, 0xab9423a7u, 0xfc93a039u,
        0x655b59c3u, 0x8f0ccc92u, 0xffeff47du, 0x85845dd1u,
        0x6fa87e4fu, 0xfe2ce6e0u, 0xa3014314u, 0x4e0811a1u,
        0xf7537e82u, 0xbd3af235u, 0x2ad7d2bbu, 0xeb86d391u
    )

    // Step 3: Process each 64-byte block
    for (chunkStart in padded.indices step 64) {
        // Break chunk into sixteen 32-bit little-endian words
        val m = UIntArray(16) { i ->
            val offset = chunkStart + i * 4
            ((padded[offset].toUInt() and 0xFFu)) or
                    ((padded[offset + 1].toUInt() and 0xFFu) shl 8) or
                    ((padded[offset + 2].toUInt() and 0xFFu) shl 16) or
                    ((padded[offset + 3].toUInt() and 0xFFu) shl 24)
        }

        var a = a0
        var b = b0
        var c = c0
        var d = d0

        for (i in 0 until 64) {
            val f: UInt
            val g: Int
            when {
                i < 16 -> {
                    f = (b and c) or (b.inv() and d)
                    g = i
                }
                i < 32 -> {
                    f = (d and b) or (d.inv() and c)
                    g = (5 * i + 1) % 16
                }
                i < 48 -> {
                    f = b xor c xor d
                    g = (3 * i + 5) % 16
                }
                else -> {
                    f = c xor (b or d.inv())
                    g = (7 * i) % 16
                }
            }

            val temp = d
            d = c
            c = b
            b = b + (a + f + k[i] + m[g]).rotateLeft(s[i])
            a = temp
        }

        a0 += a
        b0 += b
        c0 += c
        d0 += d
    }

    // Step 4: Produce the final hash value (little-endian)
    fun UInt.toLittleEndianBytes(): ByteArray = byteArrayOf(
        (this and 0xFFu).toByte(),
        ((this shr 8) and 0xFFu).toByte(),
        ((this shr 16) and 0xFFu).toByte(),
        ((this shr 24) and 0xFFu).toByte()
    )

    val digest = a0.toLittleEndianBytes() + b0.toLittleEndianBytes() +
            c0.toLittleEndianBytes() + d0.toLittleEndianBytes()

    return digest.joinToString("") { byte -> byte.toUByte().toString(16).padStart(2, '0') }
}
