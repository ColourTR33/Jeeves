import Foundation

/// Client for local Ollama-compatible LLM API.
class OllamaAPIClient {
    static let shared = OllamaAPIClient()

    func summarize(transcription: String, baseUrl: String, model: String) async throws -> SummaryData {
        let prompt = """
        Please summarise the following meeting transcription. Provide:
        1. A concise summary (2-3 paragraphs)
        2. Key points discussed (bullet points)
        3. Action items identified (bullet points)

        Format your response as:
        SUMMARY:
        [your summary here]

        KEY POINTS:
        - [point 1]
        - [point 2]

        ACTION ITEMS:
        - [action 1]
        - [action 2]

        TRANSCRIPTION:
        \(transcription)
        """

        // Try Ollama native API first
        do {
            return try await callOllamaAPI(baseUrl: baseUrl, model: model, prompt: prompt)
        } catch {
            // Fall back to OpenAI-compatible endpoint
            return try await callOpenAICompatibleAPI(baseUrl: baseUrl, model: model, prompt: prompt)
        }
    }

    private func callOllamaAPI(baseUrl: String, model: String, prompt: String) async throws -> SummaryData {
        let url = URL(string: "\(baseUrl)/api/generate")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 300

        let body: [String: Any] = [
            "model": model,
            "prompt": prompt,
            "stream": false
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, _) = try await URLSession.shared.data(for: request)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let response = json?["response"] as? String ?? ""

        return parseSummaryResponse(response, model: model)
    }

    private func callOpenAICompatibleAPI(baseUrl: String, model: String, prompt: String) async throws -> SummaryData {
        let url = URL(string: "\(baseUrl)/v1/chat/completions")!
        var request = URLRequest(url: url)
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.timeoutInterval = 300

        let body: [String: Any] = [
            "model": model,
            "messages": [
                ["role": "system", "content": "You are a meeting summarisation assistant."],
                ["role": "user", "content": prompt]
            ]
        ]
        request.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, _) = try await URLSession.shared.data(for: request)
        let json = try JSONSerialization.jsonObject(with: data) as? [String: Any]
        let choices = json?["choices"] as? [[String: Any]]
        let message = choices?.first?["message"] as? [String: Any]
        let content = message?["content"] as? String ?? ""

        return parseSummaryResponse(content, model: model)
    }

    private func parseSummaryResponse(_ response: String, model: String) -> SummaryData {
        let summarySection = extractSection(from: response, start: "SUMMARY:", end: "KEY POINTS:")
        let keyPointsSection = extractSection(from: response, start: "KEY POINTS:", end: "ACTION ITEMS:")
        let actionItemsSection = extractSection(from: response, start: "ACTION ITEMS:", end: nil)

        let keyPoints = parseBulletPoints(keyPointsSection)
        let actionItems = parseBulletPoints(actionItemsSection)

        return SummaryData(
            summary: summarySection.isEmpty ? response : summarySection,
            keyPoints: keyPoints,
            actionItems: actionItems,
            modelUsed: model
        )
    }

    private func extractSection(from text: String, start: String, end: String?) -> String {
        guard let startRange = text.range(of: start) else { return "" }
        let contentStart = startRange.upperBound

        let contentEnd: String.Index
        if let end = end, let endRange = text.range(of: end, range: contentStart..<text.endIndex) {
            contentEnd = endRange.lowerBound
        } else {
            contentEnd = text.endIndex
        }

        return String(text[contentStart..<contentEnd]).trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private func parseBulletPoints(_ text: String) -> [String] {
        text.components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { $0.hasPrefix("-") || $0.hasPrefix("•") || $0.hasPrefix("*") }
            .map { line in
                var cleaned = line
                if cleaned.hasPrefix("-") { cleaned.removeFirst() }
                else if cleaned.hasPrefix("•") { cleaned.removeFirst() }
                else if cleaned.hasPrefix("*") { cleaned.removeFirst() }
                return cleaned.trimmingCharacters(in: .whitespaces)
            }
            .filter { !$0.isEmpty }
    }
}
