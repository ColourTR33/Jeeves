import Foundation

/// Client for local Ollama-compatible LLM API.
class OllamaAPIClient {
    static let shared = OllamaAPIClient()

    func summarize(transcription: String, baseUrl: String, model: String, description: String = "", attachmentCount: Int = 0) async throws -> SummaryData {
        let words = transcription.split(separator: " ")

        // Chunked summarization for long transcripts (>4000 words)
        if words.count > 4000 {
            return try await summarizeChunked(transcription: transcription, baseUrl: baseUrl, model: model, description: description, attachmentCount: attachmentCount)
        }

        let prompt = buildPrompt(transcription: transcription, description: description, attachmentCount: attachmentCount)

        // Try Ollama native API first
        do {
            return try await callOllamaAPI(baseUrl: baseUrl, model: model, prompt: prompt)
        } catch {
            // Fall back to OpenAI-compatible endpoint
            return try await callOpenAICompatibleAPI(baseUrl: baseUrl, model: model, prompt: prompt)
        }
    }

    private func summarizeChunked(transcription: String, baseUrl: String, model: String, description: String, attachmentCount: Int) async throws -> SummaryData {
        let words = transcription.split(separator: " ")
        let chunkSize = 3000
        var chunks: [[Substring]] = []
        var i = 0
        while i < words.count {
            let end = min(i + chunkSize, words.count)
            chunks.append(Array(words[i..<end]))
            i = end
        }

        print("[Jeeves] Chunked summarization: \(words.count) words -> \(chunks.count) chunks")

        // Phase 1: Summarize each chunk
        var chunkSummaries: [String] = []
        for (index, chunk) in chunks.enumerated() {
            let chunkText = chunk.joined(separator: " ")
            let chunkPrompt = """
            Summarize this section of a meeting transcription (part \(index + 1) of \(chunks.count)).
            Provide key points and any action items as bullet points. Be concise.

            TRANSCRIPTION SECTION:
            \(chunkText)
            """

            let response: String
            do {
                let result = try await callOllamaAPI(baseUrl: baseUrl, model: model, prompt: chunkPrompt)
                response = result.summary
            } catch {
                let result = try await callOpenAICompatibleAPI(baseUrl: baseUrl, model: model, prompt: chunkPrompt)
                response = result.summary
            }
            chunkSummaries.append("--- Part \(index + 1) ---\n\(response)")
        }

        // Phase 2: Final synthesis
        let combined = chunkSummaries.joined(separator: "\n\n")
        let contextSection = description.isEmpty ? "" : "\nMeeting context/agenda: \(description)\n"
        let attachmentNote = attachmentCount > 0 ? "\n\(attachmentCount) screenshot(s) were captured.\n" : ""

        let finalPrompt = """
        Below are summaries of different parts of a meeting. Combine them into a single cohesive meeting summary.
        \(contextSection)\(attachmentNote)
        Provide:
        1. A concise summary (2-3 paragraphs)
        2. Key points discussed (bullet points)
        3. Action items identified (bullet points)
        4. Questions raised (bullet points)
        5. At least 2 hashtag tags for categorizing this meeting (e.g., #project-name #sprint-review)

        Format your response as:
        SUMMARY:
        [your summary here]

        KEY POINTS:
        - [point 1]

        ACTION ITEMS:
        - [action 1]

        QUESTIONS:
        - [question 1]

        TAGS:
        #tag1 #tag2

        SECTION SUMMARIES:
        \(combined)
        """

        do {
            return try await callOllamaAPI(baseUrl: baseUrl, model: model, prompt: finalPrompt)
        } catch {
            return try await callOpenAICompatibleAPI(baseUrl: baseUrl, model: model, prompt: finalPrompt)
        }
    }

    private func buildPrompt(transcription: String, description: String, attachmentCount: Int) -> String {
        let contextSection = description.isEmpty ? "" : "\nMEETING CONTEXT/AGENDA:\n\(description)\n"
        let attachmentNote = attachmentCount > 0 ? "\nNote: \(attachmentCount) screenshot(s) were captured during this meeting.\n" : ""

        return """
        Please summarise the following meeting transcription. Provide:
        1. A concise summary (2-3 paragraphs)
        2. Key points discussed (bullet points)
        3. Action items identified (bullet points)
        4. Questions raised (bullet points)
        5. At least 2 hashtag tags for categorizing this meeting (e.g., #project-name #sprint-review #design-discussion)

        Format your response as:
        SUMMARY:
        [your summary here]

        KEY POINTS:
        - [point 1]
        - [point 2]

        ACTION ITEMS:
        - [action 1]
        - [action 2]

        QUESTIONS:
        - [question 1]
        - [question 2]

        TAGS:
        #tag1 #tag2 #tag3
        \(contextSection)\(attachmentNote)
        TRANSCRIPTION:
        \(transcription)
        """
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
        let actionItemsSection = extractSection(from: response, start: "ACTION ITEMS:", end: "QUESTIONS:")
        let questionsSection = extractSection(from: response, start: "QUESTIONS:", end: "TAGS:")
        let tagsSection = extractSection(from: response, start: "TAGS:", end: nil)

        let keyPoints = parseBulletPoints(keyPointsSection)
        let actionItems = parseBulletPoints(actionItemsSection)
        let questions = parseBulletPoints(questionsSection)
        let tags = parseHashtags(tagsSection)

        return SummaryData(
            summary: summarySection.isEmpty ? response : summarySection,
            keyPoints: keyPoints,
            actionItems: actionItems,
            questions: questions,
            tags: tags,
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

    private func parseHashtags(_ text: String) -> [String] {
        let regex = try? NSRegularExpression(pattern: "#[\\w-]+", options: [])
        let matches = regex?.matches(in: text, range: NSRange(text.startIndex..., in: text)) ?? []
        return matches.compactMap { match in
            guard let range = Range(match.range, in: text) else { return nil }
            return String(text[range]).replacingOccurrences(of: "#", with: "")
        }
    }
}
