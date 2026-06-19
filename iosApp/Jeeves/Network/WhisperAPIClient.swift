import Foundation

/// Client for local Whisper-compatible transcription API.
class WhisperAPIClient {
    static let shared = WhisperAPIClient()

    func transcribe(audioFilePath: String, baseUrl: String, model: String) async throws -> String {
        let url = URL(string: "\(baseUrl)/v1/audio/transcriptions")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.timeoutInterval = 300 // 5 minutes for long recordings

        let boundary = UUID().uuidString
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")

        let audioData = try Data(contentsOf: URL(fileURLWithPath: audioFilePath))
        let fileName = URL(fileURLWithPath: audioFilePath).lastPathComponent

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
        body.append("text\r\n".data(using: .utf8)!)

        // Language field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"language\"\r\n\r\n".data(using: .utf8)!)
        body.append("en\r\n".data(using: .utf8)!)

        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        request.httpBody = body

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse,
              httpResponse.statusCode == 200 else {
            throw TranscriptionError.serverError
        }

        guard let text = String(data: data, encoding: .utf8) else {
            throw TranscriptionError.invalidResponse
        }

        return text
    }
}

enum TranscriptionError: Error, LocalizedError {
    case serverError
    case invalidResponse

    var errorDescription: String? {
        switch self {
        case .serverError: return "Whisper server returned an error"
        case .invalidResponse: return "Invalid response from Whisper server"
        }
    }
}
