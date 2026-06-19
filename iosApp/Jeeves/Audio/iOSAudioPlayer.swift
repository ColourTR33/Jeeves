import Foundation
import AVFoundation
import Combine

/// iOS audio player using AVAudioPlayer.
class iOSAudioPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    @Published var isPlaying = false
    @Published var positionMs: Int = 0
    @Published var durationMs: Int = 0

    private var audioPlayer: AVAudioPlayer?
    private var timer: Timer?

    func play(filePath: String) {
        stop()

        let url = URL(fileURLWithPath: filePath)
        guard FileManager.default.fileExists(atPath: filePath) else { return }

        do {
            try AVAudioSession.sharedInstance().setCategory(.playback)
            try AVAudioSession.sharedInstance().setActive(true)

            audioPlayer = try AVAudioPlayer(contentsOf: url)
            audioPlayer?.delegate = self
            audioPlayer?.prepareToPlay()
            durationMs = Int(audioPlayer?.duration ?? 0) * 1000
            audioPlayer?.play()
            isPlaying = true
            startPositionTracking()
        } catch {
            print("Playback failed: \(error.localizedDescription)")
        }
    }

    func pause() {
        audioPlayer?.pause()
        isPlaying = false
        stopPositionTracking()
    }

    func resume() {
        audioPlayer?.play()
        isPlaying = true
        startPositionTracking()
    }

    func stop() {
        stopPositionTracking()
        audioPlayer?.stop()
        audioPlayer = nil
        isPlaying = false
        positionMs = 0
    }

    func seekTo(positionMs: Int) {
        let seconds = Double(positionMs) / 1000.0
        audioPlayer?.currentTime = seconds
        self.positionMs = positionMs
    }

    // MARK: - AVAudioPlayerDelegate

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        isPlaying = false
        positionMs = durationMs
        stopPositionTracking()
    }

    // MARK: - Position tracking

    private func startPositionTracking() {
        stopPositionTracking()
        timer = Timer.scheduledTimer(withTimeInterval: 0.1, repeats: true) { [weak self] _ in
            guard let self = self, let player = self.audioPlayer else { return }
            self.positionMs = Int(player.currentTime * 1000)
        }
    }

    private func stopPositionTracking() {
        timer?.invalidate()
        timer = nil
    }
}
