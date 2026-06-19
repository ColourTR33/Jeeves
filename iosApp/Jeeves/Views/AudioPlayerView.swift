import SwiftUI

struct AudioPlayerView: View {
    @EnvironmentObject var appState: AppStateManager
    @ObservedObject private var player: iOSAudioPlayer
    let filePath: String

    init(filePath: String) {
        self.filePath = filePath
        // Will be overwritten in onAppear via EnvironmentObject
        self.player = iOSAudioPlayer()
    }

    var body: some View {
        let audioPlayer = appState.audioPlayer

        VStack(spacing: 8) {
            HStack(spacing: 16) {
                // Play/Pause
                Button(action: {
                    if audioPlayer.isPlaying {
                        audioPlayer.pause()
                    } else if audioPlayer.positionMs > 0 && audioPlayer.positionMs < audioPlayer.durationMs {
                        audioPlayer.resume()
                    } else {
                        audioPlayer.play(filePath: filePath)
                    }
                }) {
                    Image(systemName: audioPlayer.isPlaying ? "pause.circle.fill" : "play.circle.fill")
                        .font(.system(size: 40))
                }

                // Stop
                Button(action: { audioPlayer.stop() }) {
                    Image(systemName: "stop.circle")
                        .font(.system(size: 28))
                }

                Spacer()

                // Time display
                Text("\(formatTime(audioPlayer.positionMs)) / \(formatTime(audioPlayer.durationMs))")
                    .font(.caption)
                    .foregroundColor(.secondary)
                    .monospacedDigit()
            }

            // Seek slider
            Slider(
                value: Binding(
                    get: {
                        audioPlayer.durationMs > 0
                            ? Double(audioPlayer.positionMs) / Double(audioPlayer.durationMs)
                            : 0
                    },
                    set: { newValue in
                        let seekMs = Int(newValue * Double(audioPlayer.durationMs))
                        audioPlayer.seekTo(positionMs: seekMs)
                    }
                ),
                in: 0...1
            )
        }
        .padding()
        .background(Color(.systemGray6))
        .cornerRadius(12)
    }

    private func formatTime(_ ms: Int) -> String {
        let totalSeconds = ms / 1000
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60
        return String(format: "%d:%02d", minutes, seconds)
    }
}
