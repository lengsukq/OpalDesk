@preconcurrency import AVFoundation
import CoreImage
import Foundation
import ImageIO

/// Low-overhead macOS webcam MJPEG capturer for OpalCast v1.2 Root-gated camera mapping.
/// Captures frames from the default Mac camera at ~15 fps (640x480) and encodes compact JPEG
/// payloads for uplink `0x30 CAMERA_FRAME`.
public final class CameraStreamCapturer: NSObject, @unchecked Sendable, AVCaptureVideoDataOutputSampleBufferDelegate {
    public static let defaultWidth: Int = 640
    public static let defaultHeight: Int = 480
    public static let defaultFps: Int = 15

    private let lock = NSLock()
    private let captureQueue = DispatchQueue(label: "io.github.lengsukq.opaldesk.camera", qos: .userInitiated)
    private let ciContext = CIContext(options: [.useSoftwareRenderer: false])
    private let colorSpace = CGColorSpaceCreateDeviceRGB()

    private var session: AVCaptureSession?
    private var onJpegFrame: (@Sendable (Data) -> Void)?
    private var minFrameIntervalSec: TimeInterval = 1.0 / 15.0
    private var lastEmitTime: CFAbsoluteTime = 0
    private var isRunning: Bool = false

    public override init() {
        super.init()
    }

    deinit {
        stop()
    }

    public var running: Bool {
        lock.lock()
        defer { lock.unlock() }
        return isRunning
    }

    /// Requests macOS camera permission if not yet determined.
    public static func requestPermission() async -> Bool {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            return true
        case .notDetermined:
            return await AVCaptureDevice.requestAccess(for: .video)
        case .denied, .restricted:
            return false
        @unknown default:
            return false
        }
    }

    public func start(
        fps: Int = CameraStreamCapturer.defaultFps,
        onJpegFrame: @escaping @Sendable (Data) -> Void
    ) throws {
        stop()

        guard let device = AVCaptureDevice.default(for: .video) else {
            throw NSError(
                domain: "OpalDesk.Camera",
                code: -1,
                userInfo: [NSLocalizedDescriptionKey: "未检测到可用的 Mac 摄像头设备"]
            )
        }

        let input = try AVCaptureDeviceInput(device: device)
        let captureSession = AVCaptureSession()
        captureSession.beginConfiguration()
        if captureSession.canSetSessionPreset(.vga640x480) {
            captureSession.sessionPreset = .vga640x480
        } else if captureSession.canSetSessionPreset(.medium) {
            captureSession.sessionPreset = .medium
        }

        guard captureSession.canAddInput(input) else {
            captureSession.commitConfiguration()
            throw NSError(
                domain: "OpalDesk.Camera",
                code: -2,
                userInfo: [NSLocalizedDescriptionKey: "无法添加 Mac 摄像头输入"]
            )
        }
        captureSession.addInput(input)

        let output = AVCaptureVideoDataOutput()
        output.alwaysDiscardsLateVideoFrames = true
        output.videoSettings = [
            kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA
        ]
        output.setSampleBufferDelegate(self, queue: captureQueue)
        guard captureSession.canAddOutput(output) else {
            captureSession.commitConfiguration()
            throw NSError(
                domain: "OpalDesk.Camera",
                code: -3,
                userInfo: [NSLocalizedDescriptionKey: "无法添加 Mac 摄像头输出"]
            )
        }
        captureSession.addOutput(output)
        captureSession.commitConfiguration()

        let clampedFps = max(5, min(30, fps))
        lock.lock()
        self.session = captureSession
        self.onJpegFrame = onJpegFrame
        self.minFrameIntervalSec = 1.0 / Double(clampedFps)
        self.lastEmitTime = 0
        self.isRunning = true
        lock.unlock()

        captureQueue.async {
            captureSession.startRunning()
        }
    }

    public func stop() {
        lock.lock()
        let activeSession = session
        session = nil
        onJpegFrame = nil
        isRunning = false
        lock.unlock()

        if let activeSession {
            captureQueue.async {
                if activeSession.isRunning {
                    activeSession.stopRunning()
                }
            }
        }
    }

    public func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        let now = CFAbsoluteTimeGetCurrent()
        lock.lock()
        guard isRunning, let callback = onJpegFrame else {
            lock.unlock()
            return
        }
        if now - lastEmitTime < minFrameIntervalSec {
            lock.unlock()
            return
        }
        lastEmitTime = now
        lock.unlock()

        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let ciImage = CIImage(cvPixelBuffer: pixelBuffer)
        let options: [CIImageRepresentationOption: Any] = [
            kCGImageDestinationLossyCompressionQuality as CIImageRepresentationOption: 0.72
        ]
        guard let jpegData = ciContext.jpegRepresentation(of: ciImage, colorSpace: colorSpace, options: options),
              !jpegData.isEmpty else {
            return
        }
        callback(jpegData)
    }
}
