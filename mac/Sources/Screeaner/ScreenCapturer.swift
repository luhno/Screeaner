import CoreMedia
import CoreVideo
import Foundation
import ScreenCaptureKit

enum CaptureError: LocalizedError {
    case displayNotFound

    var errorDescription: String? { "Display virtual não encontrado pelo ScreenCaptureKit" }
}

/// Captures one display with ScreenCaptureKit. Frames arrive as IOSurface-backed
/// NV12 pixel buffers, which VideoToolbox consumes without any copy.
final class ScreenCapturer: NSObject, SCStreamOutput, SCStreamDelegate {
    private let queue: DispatchQueue
    private var stream: SCStream?

    /// Called on `queue` for every frame whose content actually changed.
    var onFrame: ((CVPixelBuffer) -> Void)?
    var onStop: ((Error) -> Void)?

    init(queue: DispatchQueue) {
        self.queue = queue
    }

    func start(displayID: CGDirectDisplayID, width: Int, height: Int, fps: Int) async throws {
        // A freshly created virtual display takes a moment to be listed.
        var target: SCDisplay?
        for _ in 0..<30 {
            let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
            target = content.displays.first { $0.displayID == displayID }
            if target != nil { break }
            try await Task.sleep(nanoseconds: 100_000_000)
        }
        guard let target else { throw CaptureError.displayNotFound }

        let config = SCStreamConfiguration()
        config.width = width
        config.height = height
        config.minimumFrameInterval = CMTime(value: 1, timescale: CMTimeScale(fps))
        config.pixelFormat = kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
        config.showsCursor = true
        config.colorSpaceName = CGColorSpace.sRGB
        // Size of the surface pool, not a latency buffer. We keep one frame
        // around for keyframe re-sends and the encoder may hold another.
        config.queueDepth = 5

        let filter = SCContentFilter(display: target, excludingWindows: [])
        let stream = SCStream(filter: filter, configuration: config, delegate: self)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
        try await stream.startCapture()
        self.stream = stream
    }

    func stop() {
        let stream = self.stream
        self.stream = nil
        stream?.stopCapture { _ in }
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        guard type == .screen, sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
                as? [[SCStreamFrameInfo: Any]],
              let rawStatus = attachments.first?[.status] as? Int,
              SCFrameStatus(rawValue: rawStatus) == .complete,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer)
        else { return }
        onFrame?(pixelBuffer)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        onStop?(error)
    }
}
