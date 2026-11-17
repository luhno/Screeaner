import Foundation

/// Picks the encoder bitrate once per second from what the link reports:
/// back off quickly on any sign of congestion, climb back slowly when clean.
public struct BitrateController {
    public struct Sample {
        /// Frames skipped because the TCP socket was behind.
        public var skippedFrames = 0
        /// Frames the tablet could not rebuild (UDP).
        public var lostFrames = 0
        /// Fraction of datagrams that never arrived, before FEC repair.
        public var packetLoss = 0.0
        /// Round trip measured by the tablet, if it reported one.
        public var rttMs: Double?

        public init(skippedFrames: Int = 0, lostFrames: Int = 0, packetLoss: Double = 0, rttMs: Double? = nil) {
            self.skippedFrames = skippedFrames
            self.lostFrames = lostFrames
            self.packetLoss = packetLoss
            self.rttMs = rttMs
        }
    }

    public let maximum: Int
    public let minimum: Int
    public private(set) var current: Int
    private var cleanSeconds = 0
    private var bestRttMs = Double.infinity

    /// `start`: where to begin (e.g. lower over the internet); the ceiling stays `maximum`.
    public init(maximum: Int, minimum: Int = 2_000_000, start: Int? = nil) {
        self.maximum = max(maximum, minimum)
        self.minimum = minimum
        self.current = min(max(start ?? maximum, minimum), max(maximum, minimum))
    }

    /// Returns the new bitrate when it changed, nil otherwise.
    public mutating func update(_ sample: Sample) -> Int? {
        var congested = sample.skippedFrames >= 3 || sample.lostFrames > 0 || sample.packetLoss > 0.08
        if let rtt = sample.rttMs, rtt > 0 {
            bestRttMs = min(bestRttMs, rtt)
            // A queue is building somewhere when the round trip balloons.
            if rtt > bestRttMs * 2 + 25 { congested = true }
        }

        let previous = current
        if congested {
            cleanSeconds = 0
            current = max(minimum, current / 10 * 7)
        } else {
            cleanSeconds += 1
            if cleanSeconds >= 3 {
                current = min(maximum, current / 10 * 11 + 500_000)
            }
        }
        return current == previous ? nil : current
    }

    /// How much parity to add for the loss currently observed (10% to 50%).
    public static func redundancy(forLoss loss: Double) -> Double {
        min(0.5, max(0.1, 0.1 + loss * 2.5))
    }
}
