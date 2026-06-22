import Foundation
import AVFoundation

/// Periodically reads audio chunks from the recording file, wraps them in WAV format,
/// and sends to the local Whisper server for real-time transcription preview.
class WhisperStreamingClient {
    private let baseUrl: String
    private let model: String
    private let chunkInterval: Int
    private let overlapWindow: Float

    private var timer: Timer?
    private var isRunning = false
    private var audioFilePath: String = ""
    private var lastReadOffset: Int = 0
    private var previousChunkTail: Data = Data()
    private var accumulatedTranscript: String = ""
    private var onUpdate: ((String, Bool) -> Void)?

    init(baseUrl: String, model: String, chunkInterval: Int, overlapWindow: Float) {
        self.baseUrl = baseUrl
        self.model = model
        self.chunkInterval = chunkInterval
        self.overlapWindow = overlapWindow
    }

    func start(audioFilePath: String, onUpdate: @escaping (String, Bool) -> Void) {
        self.audioFilePath = audioFilePath
        self.onUpdate = onUpdate
        self.lastReadOffset = 0
        self.previousChunkTail = Data()
        self.accumulatedTranscript = ""
        self.isRunning = true

        // Start periodic chunk extraction
        timer = Timer.scheduledTimer(withTimeInterval: TimeInterval(chunkInterval), repeats: true) { [weak self] _ in
            self?.processChunk()
        }
    }

    func stop() {
        isRunning = false
        timer?.invalidate()
        timer = nil
    }

    private func processChunk() {
        guard isRunning else { return }

        // Read new audio bytes from file since last offset
        guard let fileHandle = FileHandle(forReadingAtPath: audioFilePath) else { return }
        defer { fileHandle.closeFile() }

        fileHandle.seek(toFileOffset: UInt64(lastReadOffset))
        let newData = fileHandle.readDataToEndOfFile()

        guard newData.count > 0 else { return }
        lastReadOffset += newData.count

        // Apply overlap: prepend tail from previous chunk
        let overlappedChunk = previousChunkTail + newData

        // Store tail for next overlap
        let overlapBytes = Int(overlapWindow * 16000 * 2) // 16kHz, 16-bit mono = 2 bytes/sample
        let tailSize = min(overlapBytes, newData.count)
        if tailSize > 0 {
            previousChunkTail = newData.suffix(tailSize)
        }

        // Build WAV payload
        let wavData = buildWavPayload(pcmData: overlappedChunk)

        // Send to Whisper server
        onUpdate?(accumulatedTranscript, true)
        sendChunk(wavData: wavData)
    }

    private func sendChunk(wavData: Data) {
        let endpoints = [
            "\(baseUrl)/v1/audio/transcriptions",
            "\(baseUrl)/v1/audio/inference",
            "\(baseUrl)/inference"
        ]

        Task {
            for endpoint in endpoints {
                guard let url = URL(string: endpoint) else { continue }
                var request = URLRequest(url: url)
                request.httpMethod = "POST"
                request.timeoutInterval = 30

                let boundary = UUID().uuidString
                request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

                var body = Data()
                body.append("--\(boundary)\r\n".data(using: .utf8)!)
                body.append("Content-Disposition: form-data; name=\"file\"; filename=\"chunk.wav\"\r\n".data(using: .utf8)!)
                body.append("Content-Type: audio/wav\r\n\r\n".data(using: .utf8)!)
                body.append(wavData)
                body.append("\r\n".data(using: .utf8)!)
                body.append("--\(boundary)\r\n".data(using: .utf8)!)
                body.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n".data(using: .utf8)!)
                body.append("\(model)\r\n".data(using: .utf8)!)
                body.append("--\(boundary)\r\n".data(using: .utf8)!)
                body.append("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n".data(using: .utf8)!)
                body.append("text\r\n".data(using: .utf8)!)
                body.append("--\(boundary)--\r\n".data(using: .utf8)!)
                request.httpBody = body

                do {
                    let (data, response) = try await URLSession.shared.data(for: request)
                    guard let httpResponse = response as? HTTPURLResponse,
                          httpResponse.statusCode == 200,
                          let text = String(data: data, encoding: .utf8)?.trimmingCharacters(in: .whitespacesAndNewlines),
                          !text.isEmpty else {
                        continue
                    }

                    // Deduplicate and append
                    accumulatedTranscript = deduplicateAndAppend(existing: accumulatedTranscript, newText: text)
                    onUpdate?(accumulatedTranscript, false)
                    return
                } catch {
                    continue
                }
            }
            // All endpoints failed
            onUpdate?(accumulatedTranscript, false)
        }
    }

    /// Deduplicates overlapping text between existing transcript and new chunk.
    private func deduplicateAndAppend(existing: String, newText: String) -> String {
        if existing.isEmpty { return newText }
        if newText.isEmpty { return existing }

        let existingWords = existing.split(separator: " ").map(String.init)
        let newWords = newText.split(separator: " ").map(String.init)

        let tailWords = Array(existingWords.suffix(20))

        // Find longest suffix of tailWords matching a prefix of newWords (min 3)
        var longestMatch = 0
        for matchLen in stride(from: min(tailWords.count, newWords.count), through: 3, by: -1) {
            let suffix = Array(tailWords.suffix(matchLen))
            let prefix = Array(newWords.prefix(matchLen))
            if suffix.map({ $0.lowercased() }) == prefix.map({ $0.lowercased() }) {
                longestMatch = matchLen
                break
            }
        }

        if longestMatch >= 3 {
            let nonOverlapping = newWords.dropFirst(longestMatch).joined(separator: " ")
            return nonOverlapping.isEmpty ? existing : "\(existing) \(nonOverlapping)"
        } else {
            return "\(existing) \(newText)"
        }
    }

    /// Builds a WAV file from raw PCM data (16kHz, 16-bit, mono).
    private func buildWavPayload(pcmData: Data) -> Data {
        let sampleRate: Int32 = 16000
        let channels: Int16 = 1
        let bitsPerSample: Int16 = 16
        let byteRate = sampleRate * Int32(channels) * Int32(bitsPerSample / 8)
        let blockAlign = channels * (bitsPerSample / 8)
        let dataSize = Int32(pcmData.count)

        var header = Data(capacity: 44)
        header.append(contentsOf: [0x52, 0x49, 0x46, 0x46]) // "RIFF"
        header.append(withUnsafeBytes(of: (36 + dataSize).littleEndian) { Data($0) })
        header.append(contentsOf: [0x57, 0x41, 0x56, 0x45]) // "WAVE"
        header.append(contentsOf: [0x66, 0x6D, 0x74, 0x20]) // "fmt "
        header.append(withUnsafeBytes(of: Int32(16).littleEndian) { Data($0) }) // Subchunk1Size
        header.append(withUnsafeBytes(of: Int16(1).littleEndian) { Data($0) }) // AudioFormat PCM
        header.append(withUnsafeBytes(of: channels.littleEndian) { Data($0) })
        header.append(withUnsafeBytes(of: sampleRate.littleEndian) { Data($0) })
        header.append(withUnsafeBytes(of: byteRate.littleEndian) { Data($0) })
        header.append(withUnsafeBytes(of: blockAlign.littleEndian) { Data($0) })
        header.append(withUnsafeBytes(of: bitsPerSample.littleEndian) { Data($0) })
        header.append(contentsOf: [0x64, 0x61, 0x74, 0x61]) // "data"
        header.append(withUnsafeBytes(of: dataSize.littleEndian) { Data($0) })

        return header + pcmData
    }
}
