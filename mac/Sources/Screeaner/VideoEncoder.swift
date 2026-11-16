import CoreMedia
import CoreVideo
import Foundation
import VideoToolbox

enum EncoderError: LocalizedError {
    case createFailed(OSStatus)

    var errorDescription: String? {
        switch self {
        case .createFailed(let status): return "Falha ao criar o encoder de vídeo (\(status))"
        }
    }
}

/// Hardware H.264/HEVC encoder tuned for latency: no B-frames, no lookahead,
/// low-latency rate control, one frame in -> one frame out.
/// `encode` and `forceKeyframe` must be called from a single queue.
final class VideoEncoder {
    private var session: VTCompressionSession?
    private let codec: Codec
    private var forceKey = true
    private static let startCode: [UInt8] = [0, 0, 0, 1]

    /// (Annex-B access unit, isKeyframe, ptsMicros, encodeMicros). Called on a VideoToolbox thread.
    var onEncoded: ((Data, Bool, UInt64, UInt32) -> Void)?

    init(width: Int, height: Int, codec: Codec, fps: Int, bitrate: Int) throws {
        self.codec = codec
        let codecType = codec == .hevc ? kCMVideoCodecType_HEVC : kCMVideoCodecType_H264

        let lowLatencySpec: [CFString: Any] = [
            kVTVideoEncoderSpecification_EnableLowLatencyRateControl: true,
            kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: true,
        ]
        let fallbackSpec: [CFString: Any] = [
            kVTVideoEncoderSpecification_EnableHardwareAcceleratedVideoEncoder: true
        ]

        var created: VTCompressionSession?
        var status: OSStatus = -1
        for spec in [lowLatencySpec, fallbackSpec] {
            status = VTCompressionSessionCreate(
                allocator: nil,
                width: Int32(width),
                height: Int32(height),
                codecType: codecType,
                encoderSpecification: spec as CFDictionary,
                imageBufferAttributes: nil,
                compressedDataAllocator: nil,
                outputCallback: nil,
                refcon: nil,
                compressionSessionOut: &created
            )
            if status == noErr, created != nil { break }
        }
        guard status == noErr, let session = created else { throw EncoderError.createFailed(status) }
        self.session = session

        // Unsupported properties just return an error, which is fine to ignore.
        func set(_ key: CFString, _ value: Any) {
            VTSessionSetProperty(session, key: key, value: value as CFTypeRef)
        }
        set(kVTCompressionPropertyKey_RealTime, true)
        set(kVTCompressionPropertyKey_AllowFrameReordering, false)
        set(kVTCompressionPropertyKey_ProfileLevel,
            codec == .hevc ? kVTProfileLevel_HEVC_Main_AutoLevel : kVTProfileLevel_H264_High_AutoLevel)
        set(kVTCompressionPropertyKey_AverageBitRate, bitrate)
        set(kVTCompressionPropertyKey_ExpectedFrameRate, fps)
        // Keyframes are huge latency spikes: only send them when asked.
        set(kVTCompressionPropertyKey_MaxKeyFrameInterval, 100_000)
        set(kVTCompressionPropertyKey_MaxKeyFrameIntervalDuration, 0)
        set(kVTCompressionPropertyKey_MaxFrameDelayCount, 0)
        set(kVTCompressionPropertyKey_PrioritizeEncodingSpeedOverQuality, true)
        VTCompressionSessionPrepareToEncodeFrames(session)
    }

    /// Changes the target bitrate on the fly (adaptive bitrate).
    func setBitrate(_ bitrate: Int) {
        guard let session else { return }
        VTSessionSetProperty(session, key: kVTCompressionPropertyKey_AverageBitRate, value: bitrate as CFNumber)
    }

    func forceKeyframe() {
        forceKey = true
    }

    func encode(_ pixelBuffer: CVPixelBuffer) {
        guard let session else { return }
        let start = DispatchTime.now().uptimeNanoseconds
        let pts = CMClockGetTime(CMClockGetHostTimeClock())

        var frameProperties: CFDictionary?
        if forceKey {
            forceKey = false
            frameProperties = [kVTEncodeFrameOptionKey_ForceKeyFrame: true] as CFDictionary
        }

        VTCompressionSessionEncodeFrame(
            session,
            imageBuffer: pixelBuffer,
            presentationTimeStamp: pts,
            duration: .invalid,
            frameProperties: frameProperties,
            infoFlagsOut: nil
        ) { [weak self] status, _, sampleBuffer in
            guard let self, status == noErr, let sampleBuffer else { return }
            let elapsed = (DispatchTime.now().uptimeNanoseconds - start) / 1000
            self.emit(sampleBuffer, encodeMicros: UInt32(min(elapsed, UInt64(UInt32.max))))
        }
    }

    func invalidate() {
        guard let session else { return }
        self.session = nil
        VTCompressionSessionInvalidate(session)
    }

    // MARK: - AVCC/HVCC -> Annex-B

    private func emit(_ sampleBuffer: CMSampleBuffer, encodeMicros: UInt32) {
        guard let blockBuffer = CMSampleBufferGetDataBuffer(sampleBuffer) else { return }

        var isKey = true
        if let attachments = CMSampleBufferGetSampleAttachmentsArray(sampleBuffer, createIfNecessary: false)
            as? [[CFString: Any]],
            let notSync = attachments.first?[kCMSampleAttachmentKey_NotSync] as? Bool {
            isKey = !notSync
        }

        let length = CMBlockBufferGetDataLength(blockBuffer)
        var raw = [UInt8](repeating: 0, count: length)
        guard CMBlockBufferCopyDataBytes(blockBuffer, atOffset: 0, dataLength: length, destination: &raw) == noErr
        else { return }

        var out = Data(capacity: length + 128)
        if isKey, let format = CMSampleBufferGetFormatDescription(sampleBuffer) {
            appendParameterSets(of: format, to: &out)
        }

        // Replace each 4-byte big-endian NAL length with a start code.
        var offset = 0
        while offset + 4 <= length {
            let nalLength = Int(raw[offset]) << 24 | Int(raw[offset + 1]) << 16
                | Int(raw[offset + 2]) << 8 | Int(raw[offset + 3])
            offset += 4
            guard nalLength > 0, offset + nalLength <= length else { break }
            out.append(contentsOf: Self.startCode)
            raw.withUnsafeBufferPointer { buffer in
                out.append(buffer.baseAddress! + offset, count: nalLength)
            }
            offset += nalLength
        }

        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let ptsMicros = UInt64(max(0, CMTimeGetSeconds(pts)) * 1_000_000)
        onEncoded?(out, isKey, ptsMicros, encodeMicros)
    }

    private func appendParameterSets(of format: CMFormatDescription, to out: inout Data) {
        var count = 0
        var index = 0
        repeat {
            var pointer: UnsafePointer<UInt8>?
            var size = 0
            let status: OSStatus
            if codec == .hevc {
                status = CMVideoFormatDescriptionGetHEVCParameterSetAtIndex(
                    format, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                    parameterSetSizeOut: &size, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
            } else {
                status = CMVideoFormatDescriptionGetH264ParameterSetAtIndex(
                    format, parameterSetIndex: index, parameterSetPointerOut: &pointer,
                    parameterSetSizeOut: &size, parameterSetCountOut: &count, nalUnitHeaderLengthOut: nil)
            }
            guard status == noErr, let pointer else { return }
            out.append(contentsOf: Self.startCode)
            out.append(pointer, count: size)
            index += 1
        } while index < count
    }
}
