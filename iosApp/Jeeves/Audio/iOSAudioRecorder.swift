import Foundation
import AVFoundation

/// iOS audio recorder using AVAudioRecorder.
/// Records to WAV format at 16kHz mono (optimal for Whisper).
class iOSAudioRecorder: NSObject {
    private var audioRecorder: AVAudioRecorder?
    private var audioSession: AVAudioSession?

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
        audioRecorder?.prepareToRecord()
        audioRecorder?.record()
    }

    func stopRecording() {
        audioRecorder?.stop()
        audioRecorder = nil
    }

    func pauseRecording() {
        audioRecorder?.pause()
    }

    func resumeRecording() {
        audioRecorder?.record()
    }
}
