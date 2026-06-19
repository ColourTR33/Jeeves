import SwiftUI

/// A segmented audio level meter that shows green/yellow/red bars.
struct AudioLevelMeterView: View {
    let level: Float

    private let barCount = 20
    private let barSpacing: CGFloat = 3

    var body: some View {
        GeometryReader { geometry in
            let barWidth = (geometry.size.width - CGFloat(barCount - 1) * barSpacing) / CGFloat(barCount)
            let barHeight = geometry.size.height

            HStack(spacing: barSpacing) {
                ForEach(0..<barCount, id: \.self) { index in
                    let fraction = Float(index + 1) / Float(barCount)
                    let isActive = level >= fraction - (0.5 / Float(barCount))

                    RoundedRectangle(cornerRadius: 3)
                        .fill(barColor(fraction: fraction, isActive: isActive))
                        .frame(width: barWidth, height: barHeight)
                }
            }
        }
    }

    private func barColor(fraction: Float, isActive: Bool) -> Color {
        guard isActive else {
            return Color(.systemGray5)
        }
        if fraction <= 0.6 {
            return .green
        } else if fraction <= 0.85 {
            return .yellow
        } else {
            return .red
        }
    }
}
