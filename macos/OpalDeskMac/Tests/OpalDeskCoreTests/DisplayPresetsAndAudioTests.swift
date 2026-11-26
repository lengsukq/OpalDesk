import Foundation
import Testing
@testable import OpalDeskCore

@Suite("DisplayPresets Clamping, AudioSpecificConfig, WindowActionKind & FileChunk Limits")
struct DisplayPresetsAndAudioTests {

    @Test("DisplayPresets DPI/FPS options, defaults, and clamp helpers enforce exact bounds")
    func displayPresetsOptionsAndClamping() {
        // DPI & FPS options are non-empty, strictly ascending, and include defaults.
        #expect(!DisplayPresets.dpiOptions.isEmpty)
        #expect(DisplayPresets.dpiOptions == DisplayPresets.dpiOptions.sorted())
        #expect(DisplayPresets.dpiOptions.contains(DisplayPresets.defaultDPI))

        #expect(!DisplayPresets.fpsOptions.isEmpty)
        #expect(DisplayPresets.fpsOptions == DisplayPresets.fpsOptions.sorted())
        #expect(DisplayPresets.fpsOptions.contains(DisplayPresets.defaultFPS))

        #expect(DisplayPresets.pairingPinLength == 6)
        #expect(DisplayPresets.bitrateKbpsRange.contains(DisplayPresets.defaultBitrateKbps))
        #expect(DisplayPresets.bitrateKbpsRange.lowerBound % DisplayPresets.bitrateStepKbps == 0)
        #expect(DisplayPresets.bitrateKbpsRange.upperBound % DisplayPresets.bitrateStepKbps == 0)
        #expect(DisplayPresets.singleWidthDpRange.contains(DisplayPresets.defaultSingleWidthDp))

        // Bitrate clamping (supports wired USB up to 48 Mbps)
        #expect(DisplayPresets.clampBitrateKbps(-1000) == DisplayPresets.bitrateKbpsRange.lowerBound)
        #expect(DisplayPresets.clampBitrateKbps(0) == DisplayPresets.bitrateKbpsRange.lowerBound)
        #expect(DisplayPresets.clampBitrateKbps(3999) == 4000)
        #expect(DisplayPresets.clampBitrateKbps(4000) == 4000)
        #expect(DisplayPresets.clampBitrateKbps(16000) == 16000)
        #expect(DisplayPresets.clampBitrateKbps(32000) == 32000)
        #expect(DisplayPresets.clampBitrateKbps(48000) == 48000)
        #expect(DisplayPresets.clampBitrateKbps(48001) == 48000)
        #expect(DisplayPresets.clampBitrateKbps(Int.max) == DisplayPresets.bitrateKbpsRange.upperBound)

        // HelloPayload identifies macOS platform so Android daemon uses low-power 1Hz static keepalive.
        let macHello = HelloPayload(clientName: "Mac", clientId: "mac-1")
        #expect(macHello.clientPlatform == "macos")

        // Adaptive single-app width (dp) clamping
        #expect(DisplayPresets.clampSingleWidthDp(-50) == DisplayPresets.singleWidthDpRange.lowerBound)
        #expect(DisplayPresets.clampSingleWidthDp(319) == 320)
        #expect(DisplayPresets.clampSingleWidthDp(320) == 320)
        #expect(DisplayPresets.clampSingleWidthDp(460) == 460)
        #expect(DisplayPresets.clampSingleWidthDp(1200) == 1200)
        #expect(DisplayPresets.clampSingleWidthDp(1201) == 1200)
        #expect(DisplayPresets.clampSingleWidthDp(9999) == DisplayPresets.singleWidthDpRange.upperBound)

        // Single-app width percentage clamping
        #expect(DisplayPresets.clampSingleWidthPct(-10) == DisplayPresets.singleWidthPctRange.lowerBound)
        #expect(DisplayPresets.clampSingleWidthPct(27) == 28)
        #expect(DisplayPresets.clampSingleWidthPct(28) == 28)
        #expect(DisplayPresets.clampSingleWidthPct(42) == 42)
        #expect(DisplayPresets.clampSingleWidthPct(100) == 100)
        #expect(DisplayPresets.clampSingleWidthPct(101) == 100)
        #expect(DisplayPresets.clampSingleWidthPct(500) == DisplayPresets.singleWidthPctRange.upperBound)

        // Popout window opacity clamping
        #expect(DisplayPresets.clampPopoutOpacity(-1.0) == DisplayPresets.popoutOpacityRange.lowerBound)
        #expect(DisplayPresets.clampPopoutOpacity(0.0) == 0.4)
        #expect(DisplayPresets.clampPopoutOpacity(0.39) == 0.4)
        #expect(DisplayPresets.clampPopoutOpacity(0.4) == 0.4)
        #expect(DisplayPresets.clampPopoutOpacity(0.75) == 0.75)
        #expect(DisplayPresets.clampPopoutOpacity(1.0) == 1.0)
        #expect(DisplayPresets.clampPopoutOpacity(1.01) == 1.0)
        #expect(DisplayPresets.clampPopoutOpacity(9.5) == DisplayPresets.popoutOpacityRange.upperBound)
    }

    @Test("DisplayResolutionPreset, PopoutAspectPreset, and PopoutStreamGeometry invariants hold")
    func resolutionAndPopoutPresets() {
        #expect(DisplayResolutionPreset.matchWindow.fixedSize == nil)
        #expect(DisplayResolutionPreset.fhd1080p.fixedSize?.width == 1920)
        #expect(DisplayResolutionPreset.fhd1080p.fixedSize?.height == 1080)
        #expect(DisplayResolutionPreset.qhd2K.fixedSize?.width == 2560)
        #expect(DisplayResolutionPreset.qhd2K.fixedSize?.height == 1440)
        #expect(DisplayResolutionPreset.ultrawide21x9.fixedSize?.width == 2560)
        #expect(DisplayResolutionPreset.ultrawide21x9.fixedSize?.height == 1080)
        #expect(DisplayResolutionPreset.portrait.fixedSize?.width == 1080)
        #expect(DisplayResolutionPreset.portrait.fixedSize?.height == 1920)
        for preset in DisplayResolutionPreset.allCases {
            #expect(!preset.title.isEmpty)
            if let size = preset.fixedSize {
                #expect(size.width >= CoordinateMapper.minimumDimension && size.width <= CoordinateMapper.maximumDimension)
                #expect(size.height >= CoordinateMapper.minimumDimension && size.height <= CoordinateMapper.maximumDimension)
            }
        }

        for aspect in PopoutAspectPreset.allCases {
            #expect(!aspect.title.isEmpty)
            #expect(!aspect.systemImage.isEmpty)
            #expect(aspect.windowSize.width > 0 && aspect.windowSize.height > 0)
        }
        #expect(PopoutAspectPreset.phone.windowSize.width < PopoutAspectPreset.phone.windowSize.height)
        #expect(PopoutAspectPreset.tablet.windowSize.width > PopoutAspectPreset.tablet.windowSize.height)

        // PopoutStreamGeometry.smallestWidthDp uses short edge and guards against dpi <= 0.
        let portrait = PopoutStreamGeometry(width: 848, height: 1680, dpi: 320)
        let landscape = PopoutStreamGeometry(width: 1680, height: 848, dpi: 320)
        #expect(portrait.smallestWidthDp == 424)
        #expect(landscape.smallestWidthDp == 424)
        #expect(PopoutStreamGeometry(width: 848, height: 1680, dpi: 0).smallestWidthDp == 0)
        #expect(PopoutStreamGeometry(width: 848, height: 1680, dpi: -160).smallestWidthDp == 0)
    }

    @Test("AudioSpecificConfigInfo parses valid ASC headers across rates, channels, object types, and slices")
    func audioSpecificConfigValidParsing() {
        // Helper to pack 5-bit objectType + 4-bit freqIndex + 4-bit channelConfig into 2 bytes.
        func makeASC(objectType: UInt8, freqIndex: UInt8, channelConfig: UInt8) -> Data {
            let b0 = ((objectType & 0x1F) << 3) | ((freqIndex >> 1) & 0x07)
            let b1 = ((freqIndex & 0x01) << 7) | ((channelConfig & 0x0F) << 3)
            return Data([b0, b1])
        }

        // 48kHz stereo AAC-LC (0x11 0x90)
        let aac48Stereo = makeASC(objectType: 2, freqIndex: 3, channelConfig: 2)
        #expect(aac48Stereo == Data([0x11, 0x90]))
        #expect(AudioSpecificConfigInfo.parse(aac48Stereo) == AudioSpecificConfigInfo(
            audioObjectType: 2,
            sampleRate: 48_000,
            channels: 2
        ))

        // 44.1kHz mono AAC-LC (freqIndex=4, ch=1)
        #expect(AudioSpecificConfigInfo.parse(makeASC(objectType: 2, freqIndex: 4, channelConfig: 1)) == AudioSpecificConfigInfo(
            audioObjectType: 2,
            sampleRate: 44_100,
            channels: 1
        ))

        // 96kHz 5.1 AAC-Main (obj=1, freqIndex=0, ch=6)
        #expect(AudioSpecificConfigInfo.parse(makeASC(objectType: 1, freqIndex: 0, channelConfig: 6)) == AudioSpecificConfigInfo(
            audioObjectType: 1,
            sampleRate: 96_000,
            channels: 6
        ))

        // 7350Hz (lowest table entry, freqIndex=12) with 7.1 channelConfig=7 -> 8 channels
        #expect(AudioSpecificConfigInfo.parse(makeASC(objectType: 5, freqIndex: 12, channelConfig: 7)) == AudioSpecificConfigInfo(
            audioObjectType: 5,
            sampleRate: 7_350,
            channels: 8
        ))

        // channelConfig=0 (defined in PCE) safely defaults to at least 1 channel
        #expect(AudioSpecificConfigInfo.parse(makeASC(objectType: 2, freqIndex: 3, channelConfig: 0)) == AudioSpecificConfigInfo(
            audioObjectType: 2,
            sampleRate: 48_000,
            channels: 1
        ))

        // Non-zero startIndex Data slice parses cleanly without assuming index 0.
        let container = Data([0xFF, 0xEE, 0x11, 0x90, 0xAA])
        let slice = container[2..<4]
        #expect(slice.startIndex == 2)
        #expect(AudioSpecificConfigInfo.parse(slice) == AudioSpecificConfigInfo(
            audioObjectType: 2,
            sampleRate: 48_000,
            channels: 2
        ))
    }

    @Test("AudioSpecificConfigInfo rejects short/invalid headers and enforces ESDS length bounds")
    func audioSpecificConfigInvalidAndESDSBounds() {
        #expect(AudioSpecificConfigInfo.parse(Data()) == nil)
        #expect(AudioSpecificConfigInfo.parse(Data([0x11])) == nil)

        // Frequency indices 13, 14 (reserved) and 15 (explicit 24-bit escape) are outside table.
        for badFreq: UInt8 in [13, 14, 15] {
            let b0: UInt8 = (2 << 3) | ((badFreq >> 1) & 0x07)
            let b1: UInt8 = ((badFreq & 0x01) << 7) | (2 << 3)
            #expect(AudioSpecificConfigInfo.parse(Data([b0, b1])) == nil)
        }

        // wrapInESDS edge cases
        #expect(AudioSpecificConfigInfo.wrapInESDS(Data()).isEmpty)
        #expect(AudioSpecificConfigInfo.wrapInESDS(Data(repeating: 0x11, count: 128)).isEmpty)

        // Exact 127-byte maximum ASC fits single-byte descriptor length.
        let maxASC = Data(repeating: 0x22, count: 127)
        let maxESDS = AudioSpecificConfigInfo.wrapInESDS(maxASC)
        #expect(!maxESDS.isEmpty)
        #expect(maxESDS.first == 0x03)

        // Verify descriptor tag structure on standard 2-byte ASC.
        let esds = AudioSpecificConfigInfo.wrapInESDS(Data([0x11, 0x90]))
        #expect(esds.count == 27)
        #expect(esds[0] == 0x03) // ES_Descriptor
        #expect(esds[1] == 25)   // esdBody length
        #expect(esds[5] == 0x04) // DecoderConfigDescriptor
        #expect(esds[6] == 17)   // dcdBody length
        #expect(esds[20] == 0x05) // DecoderSpecificInfo
        #expect(esds[21] == 0x02) // ASC length
        #expect(Array(esds[22...23]) == [0x11, 0x90])
        #expect(Array(esds[24...26]) == [0x06, 0x01, 0x02]) // SLConfigDescriptor
    }

    @Test("WindowActionPayload encodes every WindowActionKind case with exact wire JSON and omits nil fields")
    func windowActionTypedKindEncoding() throws {
        let expectedRawValues: [(WindowActionKind, String)] = [
            (.setAdaptiveSingle, "set_adaptive_single"),
            (.setSingleWidth, "set_single_width"),
            (.launchApp, "launch_app"),
            (.setTiling, "set_tiling"),
            (.reloadAppDpi, "reload_app_dpi"),
            (.returnToDesktop, "return_to_desktop"),
            (.setInputBackend, "set_input_backend"),
            (.focusWindow, "focus_window"),
            (.minimizeWindow, "minimize_window"),
            (.closeWindow, "close_window")
        ]
        #expect(WindowActionKind.allCases.count == expectedRawValues.count)
        for (kind, raw) in expectedRawValues {
            #expect(kind.rawValue == raw)
        }

        let cases: [(WindowActionPayload, String, Set<String>)] = [
            (.setAdaptiveSingle(enabled: false, defaultWidthDp: 10), "set_adaptive_single", ["action", "enabled", "defaultWidthDp"]),
            (.setSingleWidth(pkg: "com.demo", widthPct: 999, remember: false), "set_single_width", ["action", "pkg", "widthPct", "remember"]),
            (.launchApp(pkg: "com.demo", cls: ".Main", mode: .split, slot: 1), "launch_app", ["action", "pkg", "cls", "mode", "slot"]),
            (.setTiling(topology: .split3Cols, pkgs: ["a", "b", "c"]), "set_tiling", ["action", "topology", "pkgs", "ratios"]),
            (.reloadAppDpi(pkg: "com.demo"), "reload_app_dpi", ["action", "pkg"]),
            (.returnToDesktop(pkg: "com.demo", taskId: 42), "return_to_desktop", ["action", "pkg", "taskId"]),
            (.setInputBackend(.inject), "set_input_backend", ["action", "backend"]),
            (.focusWindow(taskId: 77, pkg: "com.demo"), "focus_window", ["action", "taskId", "pkg"]),
            (.focusWindow(taskId: 77), "focus_window", ["action", "taskId"]),
            (.minimizeWindow(taskId: 88, pkg: "com.demo"), "minimize_window", ["action", "taskId", "pkg"]),
            (.closeWindow(taskId: 99, pkg: "com.demo"), "close_window", ["action", "taskId", "pkg"])
        ]

        for (payload, expectedAction, expectedKeys) in cases {
            let encoded = try payload.encode()
            let json = try #require(try JSONSerialization.jsonObject(with: encoded) as? [String: Any])
            #expect(json["action"] as? String == expectedAction)
            #expect(Set(json.keys) == expectedKeys)
        }

        // Verify tiling default ratios for every topology and custom ratio override.
        for topology in OpalCastTilingTopology.allCases {
            let defaultTiling = WindowActionPayload.setTiling(topology: topology)
            #expect(defaultTiling.ratios == topology.defaultRatios)
        }
        let customTiling = WindowActionPayload.setTiling(topology: .split2H, pkgs: ["a", "b"], ratios: [65, 35])
        #expect(customTiling.ratios == [65, 35])
    }

    @Test("FileTransferChunkPayload honors 65535-byte UTF-8 boundary and throws fieldTooLong on overflow")
    func fileTransferChunkFilenameUTF8Boundary() throws {
        // Exact 65535-byte ASCII boundary succeeds.
        let maxASCII = String(repeating: "x", count: OpalCastProtocol.maxFileNameUTF8Bytes)
        let asciiChunk = FileTransferChunkPayload(
            transferId: 1,
            chunkFlags: .start,
            fileName: maxASCII,
            totalSize: 512
        )
        let decodedASCII = try FileTransferChunkPayload.decode(from: try asciiChunk.encode())
        #expect(decodedASCII == asciiChunk)

        // Exact 65535-byte multibyte UTF-8 boundary (21845 * 3 == 65535 bytes) succeeds.
        let maxCJK = String(repeating: "中", count: 21_845)
        #expect(maxCJK.utf8.count == OpalCastProtocol.maxFileNameUTF8Bytes)
        let cjkChunk = FileTransferChunkPayload(
            transferId: 2,
            chunkFlags: .start,
            fileName: maxCJK,
            totalSize: 1024
        )
        let decodedCJK = try FileTransferChunkPayload.decode(from: try cjkChunk.encode())
        #expect(decodedCJK == cjkChunk)

        // Multibyte UTF-8 overflow (String.count is only 21846, but UTF-8 length is 65538 > 65535).
        let overflowCJK = String(repeating: "中", count: 21_846)
        #expect(overflowCJK.count < OpalCastProtocol.maxFileNameUTF8Bytes)
        #expect(overflowCJK.utf8.count == 65_538)
        let overflowChunk = FileTransferChunkPayload(
            transferId: 3,
            chunkFlags: .start,
            fileName: overflowCJK,
            totalSize: 1024
        )
        #expect(throws: OpalCastCodecError.fieldTooLong(
            field: "fileTransferChunk.fileName",
            length: 65_538,
            maxLength: OpalCastProtocol.maxFileNameUTF8Bytes
        )) {
            _ = try overflowChunk.encode()
        }

        // Non-START chunks do not serialize fileName and must not fail.
        for flags: OpalCastFileChunkFlags in [.data, .end, .abort] {
            let chunk = FileTransferChunkPayload(
                transferId: 4,
                chunkFlags: flags,
                fileName: overflowCJK,
                data: flags == .data ? Data([0xAA, 0xBB]) : Data()
            )
            let wire = try chunk.encode()
            #expect(wire.count >= 5)
        }
    }
}
