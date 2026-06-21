import Foundation

/// Client for local Whisper-compatible transcription API.
/// Supports both whisper.cpp server and OpenAI-compatible endpoints.
class WhisperAPIClient {
    static let shared = WhisperAPIClient()

    /// Endpoints to try in order (whisper.cpp server uses /inference)
    private let endpointPaths = [
        "/v1/audio/inference",
        "/inference",
        "/v1/audio/transcriptions"
    ]

    func transcribe(audioFilePath: String, baseUrl: String, model: String) async throws -> String {
        let audioData = try Data(contentsOf: URL(fileURLWithPath: audioFilePath))
        let fileName = URL(fileURLWithPath: audioFilePath).lastPathComponent

        var lastError: Error = TranscriptionError.serverError

        for path in endpointPaths {
            guard let url = URL(string: "\(baseUrl)\(path)") else { continue }

            do {
                let result = try await sendTranscriptionRequest(
                    url: url,
                    audioData: audioData,
                    fileName: fileName,
                    model: model
                )
                return result
            } catch {
                lastError = error
                continue
            }
        }

        throw lastError
    }

    private func sendTranscriptionRequest(url: URL, audioData: Data, fileName: String, model: String) async throws -> String {
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 300

        let boundary = UUID().uuidString
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        var body = Data()

        // File field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(fileName)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: audio/wav\r\n\r\n".data(using: .utf8)!)
        body.append(audioData)
        body.append("\r\n".data(using: .utf8)!)

        // Model field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(model)\r\n".data(using: .utf8)!)

        // Response format field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n".data(using: .utf8)!)
        body.append("verbose_json\r\n".data(using: .utf8)!)

        // Language field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"language\"\r\n\r\n".data(using: .utf8)!)
        body.append("en\r\n".data(using: .utf8)!)

        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        request.httpBody = body

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw TranscriptionError.serverError
        }

        // 404 means this endpoint doesn't exist - try next one
        if httpResponse.statusCode == 404 {
            throw TranscriptionError.endpointNotFound
        }

        guard httpResponse.statusCode == 200 else {
            throw TranscriptionError.serverError
        }

        // Try to parse as JSON first (verbose_json format)
        if let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
           let text = json["text"] as? String {
            return text
        }

        // Fall back to plain text
        guard let text = String(data: data, encoding: .utf8) else {
            throw TranscriptionError.invalidResponse
        }

        return text
    }

    /// Check if the whisper server is reachable
    func checkConnectivity(baseUrl: String) async -> Bool {
        guard let url = URL(string: "\(baseUrl)/health") else { return false }
        var request = URLRequest(url: url)
        request.timeoutInterval = 3

        do {
            let (_, response) = try await URLSession.shared.data(for: request)
            if let httpResponse = response as? HTTPURLResponse {
                // Server is up if we get any response (even 404 means it's listening)
                return httpResponse.statusCode < 500
            }
            return false
        } catch {
            return false
        }
    }
}

enum TranscriptionError: Error, LocalizedError {
    case serverError
    case invalidResponse
    case endpointNotFound

    var errorDescription: String? {
        switch self {
        case .serverError: return "Whisper server returned an error"
        case .invalidResponse: return "Invalid response from Whisper server"
        case .endpointNotFound: return "Endpoint not found on server"
        }
    }
}
