import Foundation

/// Cloud transcription client using Groq's Whisper API.
/// Offers whisper-large-v3 quality at near-instant speed.
class GroqWhisperClient {
    static let shared = GroqWhisperClient()

    private let groqBaseUrl = "https://api.groq.com/openai/v1/audio/transcriptions"

    func transcribe(audioFilePath: String, apiKey: String, model: String = "whisper-large-v3-turbo") async throws -> String {
        let audioURL = URL(fileURLWithPath: audioFilePath)
        let audioData = try Data(contentsOf: audioURL)

        print("[Groq] Transcribing: \(audioURL.lastPathComponent) (\(audioData.count) bytes)")

        let boundary = UUID().uuidString
        var request = URLRequest(url: URL(string: groqBaseUrl)!)
        request.httpMethod = "POST"
        request.setValue("Bearer \(apiKey)", forHTTPHeaderField: "Authorization")
        request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 120

        var body = Data()
        // File field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"file\"; filename=\"\(audioURL.lastPathComponent)\"\r\n".data(using: .utf8)!)
        body.append("Content-Type: audio/wav\r\n\r\n".data(using: .utf8)!)
        body.append(audioData)
        body.append("\r\n".data(using: .utf8)!)
        // Model field
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"model\"\r\n\r\n".data(using: .utf8)!)
        body.append("\(model)\r\n".data(using: .utf8)!)
        // Response format
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"response_format\"\r\n\r\n".data(using: .utf8)!)
        body.append("verbose_json\r\n".data(using: .utf8)!)
        // Language
        body.append("--\(boundary)\r\n".data(using: .utf8)!)
        body.append("Content-Disposition: form-data; name=\"language\"\r\n\r\n".data(using: .utf8)!)
        body.append("en\r\n".data(using: .utf8)!)
        // End boundary
        body.append("--\(boundary)--\r\n".data(using: .utf8)!)

        request.httpBody = body

        let (data, response) = try await URLSession.shared.data(for: request)

        guard let httpResponse = response as? HTTPURLResponse else {
            throw NSError(domain: "GroqWhisper", code: -1, userInfo: [NSLocalizedDescriptionKey: "Invalid response"])
        }

        guard httpResponse.statusCode == 200 else {
            let errorBody = String(data: data, encoding: .utf8) ?? "Unknown error"
            print("[Groq] Error: HTTP \(httpResponse.statusCode): \(errorBody)")
            throw NSError(domain: "GroqWhisper", code: httpResponse.statusCode, userInfo: [NSLocalizedDescriptionKey: "Groq API error: HTTP \(httpResponse.statusCode)"])
        }

        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let text = json?["text"] as? String ?? ""
        print("[Groq] Transcription successful (\(text.count) chars)")
        return text
    }
}
