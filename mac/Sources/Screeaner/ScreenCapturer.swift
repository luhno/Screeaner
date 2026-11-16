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
    private let audioQueue = DispatchQueue(label: "screeaner.audio", qos: .userInteractive)
    private var stream: SCStream?
    private var configuration: SCStreamConfiguration?

    /// Called on `queue` for every frame whose content actually changed.
    var onFrame: ((CVPixelBuffer) -> Void)?
    /// System audio as interleaved 16-bit stereo at 48 kHz, when requested.
    var onAudio: ((Data) -> Void)?
    var onStop: ((Error) -> Void)?

    init(queue: DispatchQueue) {
        self.queue = queue
    }

    func start(displayID: CGDirectDisplayID, width: Int, height: Int, fps: Int, audio: Bool = false) async throws {
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
        // Mirroring a screen with another shape: letterbox instead of stretching.
        if #available(macOS 14.0, *) {
            config.preservesAspectRatio = true
        }

        if audio {
            config.capturesAudio = true
            config.sampleRate = 48_000
            config.channelCount = 2
            config.excludesCurrentProcessAudio = true
        }

        let filter = SCContentFilter(display: target, excludingWindows: [])
        let stream = SCStream(filter: filter, configuration: config, delegate: self)
        try stream.addStreamOutput(self, type: .screen, sampleHandlerQueue: queue)
        if audio {
            try stream.addStreamOutput(self, type: .audio, sampleHandlerQueue: audioQueue)
        }
        try await stream.startCapture()
        self.stream = stream
        self.configuration = config
    }

    /// New output size without restarting the capture (tablet rotation).
    func update(width: Int, height: Int) async throws {
        guard let stream, let config = configuration else { return }
        config.width = width
        config.height = height
        try await stream.updateConfiguration(config)
    }

    func stop() {
        let stream = self.stream
        self.stream = nil
        stream?.stopCapture { _ in }
    }

    func stream(_ stream: SCStream, didOutputSampleBuffer sampleBuffer: CMSampleBuffer, of type: SCStreamOutputType) {
        if type == .audio {
            forwardAudio(sampleBuffer)
            return
        }
        guard type == .screen, sampleBuffer.isValid,
              let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
                as? [[SCStreamFrameInfo: Any]],
              let rawStatus = attachments.first?[.status] as? Int,
              SCFrameStatus(rawValue: rawStatus) == .complete,
              let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer)
        else { return }
        onFrame?(pixelBuffer)
    }

    /// Converts ScreenCaptureKit's float PCM (usually non-interleaved) to s16 stereo.
    private func forwardAudio(_ sampleBuffer: CMSampleBuffer) {
        guard let onAudio, sampleBuffer.isValid,
              let format = CMSampleBufferGetFormatDescription(sampleBuffer),
              let description = CMAudioFormatDescriptionGetStreamBasicDescription(format)?.pointee,
              description.mFormatID == kAudioFormatLinearPCM,
              description.mFormatFlags & kAudioFormatFlagIsFloat != 0,
              description.mBitsPerChannel == 32
        else { return }
        let frames = CMSampleBufferGetNumSamples(sampleBuffer)
        guard frames > 0 else { return }

        var listSize = 0
        CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer, bufferListSizeNeededOut: &listSize, bufferListOut: nil, bufferListSize: 0,
            blockBufferStructureAllocator: nil, blockBufferBlockAllocator: nil, flags: 0, blockBufferOut: nil
        )
        guard listSize > 0 else { return }
        let raw = UnsafeMutableRawPointer.allocate(byteCount: listSize, alignment: MemoryLayout<AudioBufferList>.alignment)
        defer { raw.deallocate() }
        let list = raw.bindMemory(to: AudioBufferList.self, capacity: 1)
        var block: CMBlockBuffer?
        guard CMSampleBufferGetAudioBufferListWithRetainedBlockBuffer(
            sampleBuffer, bufferListSizeNeededOut: nil, bufferListOut: list, bufferListSize: listSize,
            blockBufferStructureAllocator: nil, blockBufferBlockAllocator: nil,
            flags: kCMSampleBufferFlag_AudioBufferList_Assure16ByteAlignment, blockBufferOut: &block
        ) == noErr else { return }

        let buffers = UnsafeMutableAudioBufferListPointer(list)
        let channels = max(1, Int(description.mChannelsPerFrame))
        let planar = description.mFormatFlags & kAudioFormatFlagIsNonInterleaved != 0
        var pointers: [UnsafePointer<Float>] = []
        for buffer in buffers {
            guard let data = buffer.mData else { return }
            pointers.append(UnsafePointer(data.assumingMemoryBound(to: Float.self)))
        }
        guard !pointers.isEmpty else { return }

        var out = Data(count: frames * 4)
        out.withUnsafeMutableBytes { bytes in
            let samples = bytes.bindMemory(to: Int16.self)
            for frame in 0..<frames {
                for channel in 0..<2 {
                    let source = min(channel, channels - 1)
                    let value = planar
                        ? pointers[min(source, pointers.count - 1)][frame]
                        : pointers[0][frame * channels + source]
                    samples[frame * 2 + channel] = Int16(max(-1, min(1, value)) * 32_767).littleEndian
                }
            }
        }
        onAudio(out)
    }

    func stream(_ stream: SCStream, didStopWithError error: Error) {
        onStop?(error)
    }
}
