import CoreMedia
import Foundation
import Testing
@testable import OpalDeskCore

@Suite("Annex-B H.264 / HEVC Parser & AVCC/HVCC Conversion")
struct AnnexBParserTests {

    // Valid Baseline 1920x1080 H.264 SPS & PPS NAL payloads (without start codes)
    private let sampleH264SPS = Data([
        0x67, 0x42, 0xC0, 0x28, 0xD9, 0x00, 0x78, 0x02,
        0x27, 0xE5, 0xC0, 0x44, 0x00, 0x00, 0x03, 0x00,
        0x04, 0x00, 0x00, 0x03, 0x00, 0xF0, 0x3C, 0x60,
        0xC6, 0x58
    ])
    private let sampleH264PPS = Data([0x68, 0xCE, 0x38, 0x80])

    @Test("Splits mixed 4-byte and 3-byte Annex-B H.264 start codes and extracts SPS/PPS")
    func splitH264AndExtractParameterSets() {
        var stream = Data()
        stream.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        stream.append(sampleH264SPS)
        stream.append(contentsOf: [0x00, 0x00, 0x01]) // 3-byte start code
        stream.append(sampleH264PPS)
        stream.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        let idr = Data([0x65, 0x88, 0x84, 0x21, 0xA0])
        stream.append(idr)

        let units = AnnexBParser.splitNALUnits(from: stream, codec: .h264)
        #expect(units.count == 3)
        #expect(units[0].nalType == AnnexBParser.h264SPS)
        #expect(units[0].data == sampleH264SPS)
        #expect(units[1].nalType == AnnexBParser.h264PPS)
        #expect(units[1].data == sampleH264PPS)
        #expect(units[2].nalType == AnnexBParser.h264IDR)
        #expect(units[2].data == idr)

        let params = AnnexBParser.extractParameterSets(from: units, codec: .h264)
        #expect(params.isComplete(for: .h264))
        #expect(params.sps == [sampleH264SPS])
        #expect(params.pps == [sampleH264PPS])

        let (avcc, isKey) = AnnexBParser.convertToLengthPrefixed(units: units, codec: .h264)
        #expect(isKey == true)
        // Only the IDR NAL should remain, prefixed with 4-byte BE length (5)
        #expect(avcc.count == 4 + idr.count)
        #expect(Array(avcc.prefix(4)) == [0x00, 0x00, 0x00, 0x05])
        #expect(avcc.dropFirst(4) == idr)
    }

    @Test("Creates valid CMVideoFormatDescription from H.264 SPS/PPS")
    func createH264FormatDescription() throws {
        let params = ExtractedParameterSets(sps: [sampleH264SPS], pps: [sampleH264PPS])
        let desc = try AnnexBParser.makeFormatDescription(from: params, codec: .h264)
        let dims = CMVideoFormatDescriptionGetDimensions(desc)
        #expect(dims.width == 1920)
        #expect(dims.height == 1080)
    }

    @Test("Parses HEVC VPS/SPS/PPS and converts IDR slice to 4-byte HVCC length prefix")
    func parseHEVCAndConvertToHVCC() {
        // HEVC NAL header: type = (byte0 >> 1) & 0x3F
        // VPS=32 -> 0x40 0x01; SPS=33 -> 0x42 0x01; PPS=34 -> 0x44 0x01; IDR_W_RADL=19 -> 0x26 0x01
        let vps = Data([0x40, 0x01, 0x0C, 0x01])
        let sps = Data([0x42, 0x01, 0x01, 0x60])
        let pps = Data([0x44, 0x01, 0xC1, 0x72])
        let slice = Data([0x26, 0x01, 0xAF, 0x10, 0x20, 0x30])

        var stream = Data()
        for nal in [vps, sps, pps, slice] {
            stream.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
            stream.append(nal)
        }

        let units = AnnexBParser.splitNALUnits(from: stream, codec: .hevc)
        #expect(units.count == 4)
        #expect(units.map(\.nalType) == [32, 33, 34, 19])

        let params = AnnexBParser.extractParameterSets(from: units, codec: .hevc)
        #expect(params.isComplete(for: .hevc))
        #expect(params.vps == [vps])
        #expect(params.sps == [sps])
        #expect(params.pps == [pps])

        let (hvcc, isKey) = AnnexBParser.convertToLengthPrefixed(units: units, codec: .hevc)
        #expect(isKey == true)
        #expect(hvcc.count == 4 + slice.count)
        #expect(Array(hvcc.prefix(4)) == [0x00, 0x00, 0x00, 0x06])
        #expect(hvcc.dropFirst(4) == slice)
    }

    @Test("Incomplete parameter sets throw AnnexBError.incompleteParameterSets")
    func incompleteParameterSetsThrow() {
        let incompleteH264 = ExtractedParameterSets(sps: [sampleH264SPS], pps: [])
        #expect(throws: AnnexBError.incompleteParameterSets(.h264)) {
            _ = try AnnexBParser.makeFormatDescription(from: incompleteH264, codec: .h264)
        }

        let incompleteHEVC = ExtractedParameterSets(vps: [], sps: [Data([0x42, 0x01])], pps: [Data([0x44, 0x01])])
        #expect(throws: AnnexBError.incompleteParameterSets(.hevc)) {
            _ = try AnnexBParser.makeFormatDescription(from: incompleteHEVC, codec: .hevc)
        }

        // Empty Data entries inside parameter sets must throw cleanly instead of force-unwrapping nil baseAddress.
        let emptyElementH264 = ExtractedParameterSets(sps: [Data()], pps: [sampleH264PPS])
        #expect(!emptyElementH264.isComplete(for: .h264))
        #expect(throws: AnnexBError.incompleteParameterSets(.h264)) {
            _ = try AnnexBParser.makeFormatDescription(from: emptyElementH264, codec: .h264)
        }
    }

    @Test("Empty parameter-set Data across H.264 PPS and HEVC VPS/SPS/PPS safely throws without crashing")
    func emptyDataParameterSetsAcrossCodecsThrowSafely() {
        let validVPS = Data([0x40, 0x01, 0x0C, 0x01])
        let validHEVCSPS = Data([0x42, 0x01, 0x01, 0x60])
        let validHEVCPPS = Data([0x44, 0x01, 0xC1, 0x72])

        let badH264Cases: [ExtractedParameterSets] = [
            ExtractedParameterSets(sps: [sampleH264SPS], pps: [Data()]),
            ExtractedParameterSets(sps: [sampleH264SPS, Data()], pps: [sampleH264PPS]),
            ExtractedParameterSets(sps: [sampleH264SPS], pps: [sampleH264PPS, Data()])
        ]
        for bad in badH264Cases {
            #expect(!bad.isComplete(for: .h264))
            #expect(throws: AnnexBError.incompleteParameterSets(.h264)) {
                _ = try AnnexBParser.makeFormatDescription(from: bad, codec: .h264)
            }
        }

        let badHEVCCases: [ExtractedParameterSets] = [
            ExtractedParameterSets(vps: [Data()], sps: [validHEVCSPS], pps: [validHEVCPPS]),
            ExtractedParameterSets(vps: [validVPS], sps: [Data()], pps: [validHEVCPPS]),
            ExtractedParameterSets(vps: [validVPS], sps: [validHEVCSPS], pps: [Data()]),
            ExtractedParameterSets(vps: [validVPS, Data()], sps: [validHEVCSPS], pps: [validHEVCPPS])
        ]
        for bad in badHEVCCases {
            #expect(!bad.isComplete(for: .hevc))
            #expect(throws: AnnexBError.incompleteParameterSets(.hevc)) {
                _ = try AnnexBParser.makeFormatDescription(from: bad, codec: .hevc)
            }
        }
    }

    @Test("splitNALUnits and convertToLengthPrefixed handle short buffers, trailing zeros, AUDs, and non-IDR/CRA slices")
    func splitAndConvertEdgeCases() {
        #expect(AnnexBParser.splitNALUnits(from: Data(), codec: .h264).isEmpty)
        #expect(AnnexBParser.splitNALUnits(from: Data([0x00, 0x00, 0x01]), codec: .h264).isEmpty)
        #expect(AnnexBParser.splitNALUnits(from: Data([0x11, 0x22, 0x33, 0x44, 0x55]), codec: .h264).isEmpty)

        // AUD (type 9) + non-IDR P-slice (type 1) with trailing zero padding before next start code
        let aud = Data([0x09, 0x10])
        let pSlice = Data([0x41, 0x9A, 0x22, 0x33])
        var h264Stream = Data([0x00, 0x00, 0x00, 0x01])
        h264Stream.append(aud)
        h264Stream.append(contentsOf: [0x00, 0x00]) // trailing zero padding before start code
        h264Stream.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        h264Stream.append(pSlice)

        let h264Units = AnnexBParser.splitNALUnits(from: h264Stream, codec: .h264)
        #expect(h264Units.count == 2)
        #expect(h264Units[0] == AnnexBNALUnit(data: aud, nalType: AnnexBParser.h264AUD))
        #expect(h264Units[1] == AnnexBNALUnit(data: pSlice, nalType: 1))

        let (avcc, isKey) = AnnexBParser.convertToLengthPrefixed(units: h264Units, codec: .h264)
        #expect(isKey == false)
        #expect(avcc.count == 4 + pSlice.count)
        #expect(avcc.dropFirst(4) == pSlice)

        // HEVC AUD (35 -> 0x46) stripped; IDR_N_LP (20 -> 0x28) & CRA (21 -> 0x2A) detected as keyframes.
        for keyType: UInt8 in [AnnexBParser.hevcIDRW, AnnexBParser.hevcIDRN, AnnexBParser.hevcCRA] {
            let audUnit = AnnexBNALUnit(data: Data([0x46, 0x01, 0x50]), nalType: AnnexBParser.hevcAUD)
            let keyUnit = AnnexBNALUnit(data: Data([keyType << 1, 0x01, 0xAA]), nalType: keyType)
            let (hvcc, hasKey) = AnnexBParser.convertToLengthPrefixed(units: [audUnit, keyUnit], codec: .hevc)
            #expect(hasKey == true)
            #expect(hvcc.dropFirst(4) == keyUnit.data)
        }
    }

    @Test("Zero-copy range scan & direct writeLengthPrefixed match legacy conversion byte-for-byte")
    func zeroCopyRangeScanAndDirectWriteParity() {
        // H.264 mixed 4-byte/3-byte start codes + SPS + PPS + AUD + trailing zero padding + IDR
        var h264Stream = Data()
        h264Stream.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        h264Stream.append(sampleH264SPS)
        h264Stream.append(contentsOf: [0x00, 0x00, 0x01])
        h264Stream.append(sampleH264PPS)
        h264Stream.append(contentsOf: [0x00, 0x00, 0x00, 0x01, 0x09, 0x10, 0x00]) // AUD + trailing zero
        h264Stream.append(contentsOf: [0x00, 0x00, 0x00, 0x01])
        let idr = Data([0x65, 0x88, 0x84, 0x21, 0xA0, 0xBB])
        h264Stream.append(idr)

        let h264Ranges = AnnexBParser.splitNALRanges(from: h264Stream, codec: .h264)
        #expect(h264Ranges.count == 4)
        #expect(AnnexBParser.hasParameterSetNAL(in: h264Ranges, codec: .h264) == true)

        let extractedH264 = AnnexBParser.extractParameterSets(from: h264Stream, ranges: h264Ranges, codec: .h264)
        #expect(extractedH264.sps == [sampleH264SPS])
        #expect(extractedH264.pps == [sampleH264PPS])

        let h264Info = AnnexBParser.inspectLengthPrefixed(ranges: h264Ranges, codec: .h264)
        let legacyH264 = AnnexBParser.convertToLengthPrefixed(
            units: AnnexBParser.splitNALUnits(from: h264Stream, codec: .h264),
            codec: .h264
        )
        #expect(h264Info.byteCount == legacyH264.data.count)
        #expect(h264Info.containsKeyframe == legacyH264.containsKeyframe)

        var directH264 = Data(count: h264Info.byteCount)
        let writtenH264 = directH264.withUnsafeMutableBytes { rawBuf -> Int in
            guard let ptr = rawBuf.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return AnnexBParser.writeLengthPrefixed(from: h264Stream, ranges: h264Ranges, codec: .h264, into: ptr)
        }
        #expect(writtenH264 == legacyH264.data.count)
        #expect(directH264 == legacyH264.data)

        // Non-config HEVC P-frame should report hasParameterSetNAL == false and write identically.
        var hevcPStream = Data([0x00, 0x00, 0x00, 0x01])
        let pSlice1 = Data([0x02, 0x01, 0x11, 0x22, 0x33]) // TRAIL_R (type 1)
        let pSlice2 = Data([0x02, 0x01, 0x44, 0x55])
        hevcPStream.append(pSlice1)
        hevcPStream.append(contentsOf: [0x00, 0x00, 0x01])
        hevcPStream.append(pSlice2)

        let hevcRanges = AnnexBParser.splitNALRanges(from: hevcPStream, codec: .hevc)
        #expect(hevcRanges.count == 2)
        #expect(AnnexBParser.hasParameterSetNAL(in: hevcRanges, codec: .hevc) == false)

        let hevcInfo = AnnexBParser.inspectLengthPrefixed(ranges: hevcRanges, codec: .hevc)
        let legacyHEVC = AnnexBParser.convertToLengthPrefixed(
            units: AnnexBParser.splitNALUnits(from: hevcPStream, codec: .hevc),
            codec: .hevc
        )
        #expect(hevcInfo.byteCount == legacyHEVC.data.count)
        #expect(hevcInfo.containsKeyframe == false)

        var directHEVC = Data(count: hevcInfo.byteCount)
        let writtenHEVC = directHEVC.withUnsafeMutableBytes { rawBuf -> Int in
            guard let ptr = rawBuf.bindMemory(to: UInt8.self).baseAddress else { return 0 }
            return AnnexBParser.writeLengthPrefixed(from: hevcPStream, ranges: hevcRanges, codec: .hevc, into: ptr)
        }
        #expect(writtenHEVC == legacyHEVC.data.count)
        #expect(directHEVC == legacyHEVC.data)
    }
}
