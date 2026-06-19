import Foundation
import AVFoundation
import Combine

/// iOS audio recorder using AVAudioRecorder.
/// Records to WAV format at 16kHz mono (optimal for Whisper).
/// Exposes real-time audio level for visual feedback.
class iOSAudioRecorder: NSObject, ObservableObject {
    @Published var audioLevel: Float = 0.0

    private var audioRecorder: AVAudioRecorder?
    private var audioSession: AVAudioSession?
    private var levelTimer: Timer?

    override init() {
        super.init()
        setupAudioSession()
    }

    private func setupAudioSession() {
        audioSession = AVAudioSession.sharedInstance()
        do {
            try audioSession?.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
            try audioSession?.setActive(true)
        } catch {
            print("Audio session setup failed: \(error.localizedDescription)")
        }
    }

    func startRecording(to path: String) throws {
        let url = URL(fileURLWithPath: path)

        let settings: [String: Any] = [
            AVFormatIDKey: Int(kAudioFormatLinearPCM),
            AVSampleRateKey: 16000.0,
            AVNumberOfChannelsKey: 1,
            AVLinearPCMBitDepthKey: 16,
            AVLinearPCMIsFloatKey: false,
            AVLinearPCMIsBigEndianKey: false
        ]

        try audioSession?.setActive(true)
        audioRecorder = try AVAudioRecorder(url: url, settings: settings)
        audioRecorder?.isMeteringEnabled = true
        audioRecorder?.prepareToRecord()
        audioRecorder?.record()

        startLevelMonitoring()
    }

    func stopRecording() {
        stopLevelMonitoring()
        audioRecorder?.stop()
        audioRecorder = nil
        audioLevel = 0
    }

    func pauseRecording() {
        audioRecorder?.pause()
        audioLevel = 0
    }

    func resumeRecording() {
        audioRecorder?.record()
    }

    // MARK: - Level monitoring

    private func startLevelMonitoring() {
        stopLevelMonitoring()
        levelTimer = Timer.scheduledTimer(withTimeInterval: 0.03, repeats: true) { [weak self] _ in
            guard let self = self, let recorder = self.audioRecorder, recorder.isRecording else {
                self?.audioLevel = 0
                return
            }
            recorder.updateMeters()
            // averagePower is in dB, typically -160 to 0
            let db = recorder.averagePower(forChannel: 0)
            // Map -60..0 dB to 0.0..1.0
            let normalised = max(0, min(1, (db + 60) / 60))
            self.audioLevel = normalised
        }
    }

    private func stopLevelMonitoring() {
        levelTimer?.invalidate()
        levelTimer = nil
    }
}
