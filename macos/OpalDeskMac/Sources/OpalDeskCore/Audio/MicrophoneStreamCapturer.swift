import AVFoundation
import Foundation
import os

/// Low-latency macOS microphone capturer streaming 16 kHz mono signed 16-bit LE PCM frames
/// (20 ms / 640-byte chunks) over OpalCast uplink `0x2D MIC_FRAME`.
public final class MicrophoneStreamCapturer: @unchecked Sendable {
    public static let defaultSampleRate: Double = 16_000
    public static let defaultChannels: AVAudioChannelCount = 1
    private static let chunkSamples: Int = 320 // 20 ms at 16 kHz
    private static let chunkBytes: Int = chunkSamples * 2

    private let lock = NSLock()
    private var engine: AVAudioEngine?
    private var converter: AVAudioConverter?
    private var targetFormat: AVAudioFormat?
    private var pendingData = Data()
    private var onPcmFrame: (@Sendable (Data) -> Void)?
    private var isRunning: Bool = false

    public init() {}

    deinit {
        stop()
    }

    public var running: Bool {
        lock.lock()
        defer { lock.unlock() }
        return isRunning
    }

    /// Requests macOS microphone permission if not yet determined.
    public static func requestPermission() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .audio) {
        case .authorized:
            return true
        case .notDetermined:
            return await AVCaptureDevice.requestAccess(for: .audio)
        case .denied, .restricted:
            return false
        @unknown default:
            return false
        }
    }

    /// Starts capturing the default macOS microphone and invokes `onPcmFrame` with 640-byte PCM16 chunks.
    public func start(
        sampleRate: Double = MicrophoneStreamCapturer.defaultSampleRate,
        onPcmFrame: @escaping @Sendable (Data) -> Void
    ) throws {
        stop()

        let engine = AVAudioEngine()
        let inputNode = engine.inputNode
        let hwFormat = inputNode.outputFormat(forBus: 0)
        guard hwFormat.sampleRate > 0, hwFormat.channelCount > 0 else {
            throw NSError(
                domain: "OpalDesk.Microphone",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "未检测到可用的 Mac 麦克风输入设备"]
            )
        }

        guard let targetFmt = AVAudioFormat(
            commonFormat: .pcmFormatInt16,
            sampleRate: sampleRate,
            channels: Self.defaultChannels,
            interleaved: true
        ) else {
            throw NSError(
                domain: "OpalDesk.Microphone",
                code: -2,
                userInfo: [NSLocalizedDescriptionKey: "无法创建 16kHz PCM16 目标音频格式"]
            )
        }

        guard let conv = AVAudioConverter(from: hwFormat, to: targetFmt) else {
            throw NSError(
                domain: "OpalDesk.Microphone",
                code: -3,
                userInfo: [NSLocalizedDescriptionKey: "无法创建麦克风音频格式转换器"]
            )
        }

        lock.lock()
        self.engine = engine
        self.converter = conv
        self.targetFormat = targetFmt
        self.pendingData.removeAll(keepingCapacity: true)
        self.onPcmFrame = onPcmFrame
        self.isRunning = true
        lock.unlock()

        let tapBufferFrames: AVAudioFrameCount = max(256, AVAudioFrameCount(hwFormat.sampleRate * 0.02))
        inputNode.installTap(onBus: 0, bufferSize: tapBufferFrames, format: hwFormat) { [weak self] buffer, _ in
            self?.processInputBuffer(buffer)
        }

        engine.prepare()
        do {
            try engine.start()
        } catch {
            stop()
            throw error
        }
    }

    public func stop() {
        lock.lock()
        let activeEngine = engine
        engine = nil
        converter = nil
        targetFormat = nil
        onPcmFrame = nil
        pendingData.removeAll(keepingCapacity: false)
        isRunning = false
        lock.unlock()

        if let activeEngine {
            activeEngine.inputNode.removeTap(onBus: 0)
            activeEngine.stop()
        }
    }

    private func processInputBuffer(_ inputBuffer: AVAudioPCMBuffer) {
        guard inputBuffer.frameLength > 0 else { return }

        lock.lock()
        guard isRunning, let conv = converter, let dstFmt = targetFormat, let callback = onPcmFrame else {
            lock.unlock()
            return
        }

        let ratio = dstFmt.sampleRate / max(inputBuffer.format.sampleRate, 1.0)
        let capacity = AVAudioFrameCount(ceil(Double(inputBuffer.frameLength) * ratio)) + 16
        guard let outBuffer = AVAudioPCMBuffer(pcmFormat: dstFmt, frameCapacity: capacity) else {
            lock.unlock()
            return
        }

        var consumed = false
        var convError: NSError?
        let status = conv.convert(to: outBuffer, error: &convError) { _, outStatus in
            if consumed {
                outStatus.pointee = .noDataNow
                return nil
            }
            consumed = true
            outStatus.pointee = .haveData
            return inputBuffer
        }

        if status == .error || convError != nil || outBuffer.frameLength == 0 {
            lock.unlock()
            return
        }

        let byteCount = Int(outBuffer.frameLength) * Int(dstFmt.streamDescription.pointee.mBytesPerFrame)
        guard let int16Ptr = outBuffer.int16ChannelData?[0], byteCount > 0 else {
            lock.unlock()
            return
        }

        pendingData.append(UnsafeBufferPointer(start: int16Ptr, count: byteCount / 2))
        var chunksToEmit: [Data] = []
        while pendingData.count >= Self.chunkBytes {
            let chunk = pendingData.prefix(Self.chunkBytes)
            chunksToEmit.append(Data(chunk))
            pendingData.removeFirst(Self.chunkBytes)
        }
        lock.unlock()

        for chunk in chunksToEmit {
            callback(chunk)
        }
    }
}
