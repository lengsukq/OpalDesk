import Foundation
import zlib

// MARK: - Streaming Random-Access ZIP Reader

internal protocol RandomAccessByteSource: Sendable {
    var totalSize: UInt64 { get }
    func read(offset: UInt64, length: Int) throws -> Data
}

internal final class FileRandomAccessSource: RandomAccessByteSource, @unchecked Sendable {
    private let handle: FileHandle
    private let lock = NSLock()
    let totalSize: UInt64

    init(url: URL) throws {
        let attrs = try FileManager.default.attributesOfItem(atPath: url.path)
        let size = (attrs[.size] as? NSNumber)?.uint64Value ?? 0
        self.handle = try FileHandle(forReadingFrom: url)
        self.totalSize = size
    }

    deinit {
        try? handle.close()
    }

    func read(offset: UInt64, length: Int) throws -> Data {
        guard length > 0 else { return Data() }
        guard offset <= totalSize, UInt64(length) <= totalSize - offset else {
            throw AndroidPackageError.invalidZipArchive("读取范围越界（offset=\(offset), len=\(length), total=\(totalSize)）")
        }
        return try lock.withLock {
            try handle.seek(toOffset: offset)
            return try handle.read(upToCount: length) ?? Data()
        }
    }
}

internal struct MemoryRandomAccessSource: RandomAccessByteSource {
    let data: Data
    var totalSize: UInt64 { UInt64(data.count) }

    func read(offset: UInt64, length: Int) throws -> Data {
        guard length > 0 else { return Data() }
        guard offset <= totalSize, UInt64(length) <= totalSize - offset else {
            throw AndroidPackageError.invalidZipArchive("内存切片范围越界")
        }
        let start = data.startIndex + Int(offset)
        return Data(data[start..<(start + length)])
    }
}

internal struct SliceRandomAccessSource: RandomAccessByteSource {
    let parent: any RandomAccessByteSource
    let baseOffset: UInt64
    let length: UInt64

    var totalSize: UInt64 { length }

    func read(offset: UInt64, length: Int) throws -> Data {
        guard length > 0 else { return Data() }
        guard offset <= self.length, UInt64(length) <= self.length - offset else {
            throw AndroidPackageError.invalidZipArchive("嵌套 ZIP 切片越界")
        }
        return try parent.read(offset: baseOffset + offset, length: length)
    }
}

internal struct ZipCentralDirectoryEntry: Sendable {
    let name: String
    let compressionMethod: UInt16
    let crc32: UInt32
    let compressedSize: UInt64
    let uncompressedSize: UInt64
    let localHeaderOffset: UInt64

    var isDirectory: Bool {
        name.hasSuffix("/")
    }
}

/// Zero-dependency random-access ZIP reader supporting ZIP32, ZIP64, STORED (0), DEFLATED (8),
/// bounded streaming decompression, and APK Signing Block v2/v3 detection.
internal final class StreamingZipArchive: Sendable {
    private static let sigEOCD: UInt32 = 0x0605_4B50
    private static let sigZip64Locator: UInt32 = 0x0706_4B50
    private static let sigZip64EOCD: UInt32 = 0x0606_4B50
    private static let sigCentralDirectory: UInt32 = 0x0201_4B50
    private static let sigLocalFileHeader: UInt32 = 0x0403_4B50

    let source: any RandomAccessByteSource
    let entries: [ZipCentralDirectoryEntry]
    private let entryLookup: [String: ZipCentralDirectoryEntry]
    let centralDirectoryOffset: UInt64

    init(source: any RandomAccessByteSource, limits: AndroidPackageInspectionLimits) throws {
        self.source = source
        guard source.totalSize >= 22 else {
            throw AndroidPackageError.invalidZipArchive("文件过小，不是有效的 ZIP 归档")
        }

        // Locate End of Central Directory (EOCD) inside the trailing 65,557 bytes.
        let tailLen = Int(min(source.totalSize, 65_557))
        let tailOffset = source.totalSize - UInt64(tailLen)
        let tailData = try source.read(offset: tailOffset, length: tailLen)

        guard let eocdRelative = Self.findEOCD(in: tailData) else {
            throw AndroidPackageError.invalidZipArchive("未找到 ZIP End of Central Directory 标记")
        }

        let eocdReader = LittleEndianReader(data: tailData)
        var entryCount = UInt64(try eocdReader.readUInt16(at: eocdRelative + 10))
        var cdSize = UInt64(try eocdReader.readUInt32(at: eocdRelative + 12))
        var cdOffset = UInt64(try eocdReader.readUInt32(at: eocdRelative + 16))

        // Handle ZIP64 EOCD if sentinel fields are set.
        if entryCount == 0xFFFF || cdSize == 0xFFFF_FFFF || cdOffset == 0xFFFF_FFFF {
            let eocdAbsolute = tailOffset + UInt64(eocdRelative)
            if eocdAbsolute >= 20 {
                let locatorData = try source.read(offset: eocdAbsolute - 20, length: 20)
                let locReader = LittleEndianReader(data: locatorData)
                if (try? locReader.readUInt32(at: 0)) == Self.sigZip64Locator {
                    let zip64EocdOffset = Self.readUInt64LE(locatorData, at: 8)
                    if zip64EocdOffset + 56 <= source.totalSize {
                        let z64Data = try source.read(offset: zip64EocdOffset, length: 56)
                        if Self.readUInt32LE(z64Data, at: 0) == Self.sigZip64EOCD {
                            entryCount = Self.readUInt64LE(z64Data, at: 32)
                            cdSize = Self.readUInt64LE(z64Data, at: 40)
                            cdOffset = Self.readUInt64LE(z64Data, at: 48)
                        }
                    }
                }
            }
        }

        guard entryCount <= UInt64(limits.maxCentralDirectoryEntries) else {
            throw AndroidPackageError.tooManyArchiveEntries(
                count: Int(min(entryCount, UInt64(Int.max))),
                limit: limits.maxCentralDirectoryEntries
            )
        }
        guard cdSize <= UInt64(limits.maxCentralDirectoryBytes) else {
            throw AndroidPackageError.entryTooLarge(
                entry: "ZIP Central Directory",
                limit: limits.maxCentralDirectoryBytes
            )
        }
        guard cdOffset <= source.totalSize, cdSize <= source.totalSize - cdOffset else {
            throw AndroidPackageError.invalidZipArchive("Central Directory 偏移越界")
        }

        self.centralDirectoryOffset = cdOffset
        let cdData = try source.read(offset: cdOffset, length: Int(cdSize))
        let parsed = try Self.parseCentralDirectory(cdData, expectedCount: Int(entryCount), limit: limits.maxCentralDirectoryEntries)
        self.entries = parsed

        var lookup: [String: ZipCentralDirectoryEntry] = [:]
        lookup.reserveCapacity(parsed.count)
        for item in parsed where lookup[item.name] == nil {
            lookup[item.name] = item
        }
        self.entryLookup = lookup
    }

    func entry(named name: String) -> ZipCentralDirectoryEntry? {
        entryLookup[name]
    }

    /// Resolves the exact byte offset where the entry's compressed/stored payload begins.
    func resolveLocalDataOffset(for entry: ZipCentralDirectoryEntry) throws -> UInt64 {
        guard entry.localHeaderOffset + 30 <= source.totalSize else {
            throw AndroidPackageError.invalidZipArchive("Local File Header 偏移越界：\(entry.name)")
        }
        let header = try source.read(offset: entry.localHeaderOffset, length: 30)
        guard Self.readUInt32LE(header, at: 0) == Self.sigLocalFileHeader else {
            throw AndroidPackageError.invalidZipArchive("Local File Header 魔数不匹配：\(entry.name)")
        }
        let nameLen = UInt64(Self.readUInt16LE(header, at: 26))
        let extraLen = UInt64(Self.readUInt16LE(header, at: 28))
        let dataOffset = entry.localHeaderOffset + 30 + nameLen + extraLen
        guard dataOffset <= source.totalSize else {
            throw AndroidPackageError.invalidZipArchive("条目数据偏移越界：\(entry.name)")
        }
        return dataOffset
    }

    /// Reads and (if deflated) stream-inflates an archive entry with a strict anti-zip-bomb ceiling.
    func readEntry(_ entry: ZipCentralDirectoryEntry, maxBytes: Int) throws -> Data {
        if entry.uncompressedSize > UInt64(maxBytes) {
            throw AndroidPackageError.entryTooLarge(entry: entry.name, limit: maxBytes)
        }
        let dataOffset = try resolveLocalDataOffset(for: entry)

        switch entry.compressionMethod {
        case 0: // STORED
            let byteCount = Int(entry.compressedSize)
            guard byteCount <= maxBytes else {
                throw AndroidPackageError.entryTooLarge(entry: entry.name, limit: maxBytes)
            }
            return try source.read(offset: dataOffset, length: byteCount)

        case 8: // DEFLATED (raw RFC 1951 via zlib)
            return try inflateEntry(entry: entry, dataOffset: dataOffset, maxBytes: maxBytes)

        default:
            throw AndroidPackageError.unsupportedCompressionMethod(entry.compressionMethod, entry: entry.name)
        }
    }

    private func inflateEntry(
        entry: ZipCentralDirectoryEntry,
        dataOffset: UInt64,
        maxBytes: Int
    ) throws -> Data {
        var stream = z_stream()
        let initStatus = inflateInit2_(
            &stream,
            -MAX_WBITS,
            ZLIB_VERSION,
            Int32(MemoryLayout<z_stream>.size)
        )
        guard initStatus == Z_OK else {
            throw AndroidPackageError.decompressionFailed(entry: entry.name, status: initStatus)
        }
        defer { inflateEnd(&stream) }

        var output = Data()
        let reserveHint = Int(min(entry.uncompressedSize, UInt64(min(maxBytes, 1_048_576))))
        output.reserveCapacity(reserveHint)

        let readChunkSize = 65_536
        let outBufferSize = 65_536
        var outBuffer = [UInt8](repeating: 0, count: outBufferSize)
        var remaining = entry.compressedSize
        var cursor = dataOffset

        while true {
            let chunkLen = Int(min(remaining, UInt64(readChunkSize)))
            let compressedChunk: Data
            if chunkLen > 0 {
                compressedChunk = try source.read(offset: cursor, length: chunkLen)
                cursor += UInt64(compressedChunk.count)
                remaining -= UInt64(compressedChunk.count)
            } else {
                compressedChunk = Data()
            }

            var inflateResult: Int32 = Z_OK
            var hitLimit = false

            compressedChunk.withUnsafeBytes { rawIn in
                let inPtr = rawIn.bindMemory(to: Bytef.self).baseAddress
                stream.next_in = UnsafeMutablePointer(mutating: inPtr)
                stream.avail_in = uInt(compressedChunk.count)

                repeat {
                    outBuffer.withUnsafeMutableBytes { rawOut in
                        let outPtr = rawOut.bindMemory(to: Bytef.self).baseAddress
                        stream.next_out = outPtr
                        stream.avail_out = uInt(outBufferSize)
                        inflateResult = inflate(&stream, Z_NO_FLUSH)
                    }

                    let produced = outBufferSize - Int(stream.avail_out)
                    if produced > 0 {
                        if output.count + produced > maxBytes {
                            hitLimit = true
                            return
                        }
                        output.append(outBuffer, count: produced)
                    }
                } while stream.avail_out == 0 && inflateResult == Z_OK
            }

            if hitLimit {
                throw AndroidPackageError.entryTooLarge(entry: entry.name, limit: maxBytes)
            }

            if inflateResult == Z_STREAM_END {
                break
            }
            if inflateResult != Z_OK && inflateResult != Z_BUF_ERROR {
                throw AndroidPackageError.decompressionFailed(entry: entry.name, status: inflateResult)
            }
            if remaining == 0 && compressedChunk.isEmpty {
                break
            }
        }

        return output
    }

    // MARK: - Binary Helpers

    private static func findEOCD(in tail: Data) -> Int? {
        guard tail.count >= 22 else { return nil }
        for idx in stride(from: tail.count - 22, through: 0, by: -1) {
            if readUInt32LE(tail, at: idx) == sigEOCD {
                let commentLen = Int(readUInt16LE(tail, at: idx + 20))
                if idx + 22 + commentLen == tail.count {
                    return idx
                }
            }
        }
        return nil
    }

    private static func parseCentralDirectory(
        _ data: Data,
        expectedCount: Int,
        limit: Int
    ) throws -> [ZipCentralDirectoryEntry] {
        var entries: [ZipCentralDirectoryEntry] = []
        entries.reserveCapacity(min(expectedCount, limit))
        var offset = 0

        while offset + 46 <= data.count {
            guard readUInt32LE(data, at: offset) == sigCentralDirectory else {
                break
            }
            if entries.count >= limit {
                throw AndroidPackageError.tooManyArchiveEntries(count: entries.count + 1, limit: limit)
            }

            let method = readUInt16LE(data, at: offset + 10)
            let crc32 = readUInt32LE(data, at: offset + 16)
            var compressedSize = UInt64(readUInt32LE(data, at: offset + 20))
            var uncompressedSize = UInt64(readUInt32LE(data, at: offset + 24))
            let nameLen = Int(readUInt16LE(data, at: offset + 28))
            let extraLen = Int(readUInt16LE(data, at: offset + 30))
            let commentLen = Int(readUInt16LE(data, at: offset + 32))
            var localHeaderOffset = UInt64(readUInt32LE(data, at: offset + 42))

            let recordEnd = offset + 46 + nameLen + extraLen + commentLen
            guard recordEnd <= data.count else {
                throw AndroidPackageError.invalidZipArchive("Central Directory 记录被截断")
            }

            let nameStart = data.startIndex + offset + 46
            let nameSlice = data[nameStart..<(nameStart + nameLen)]
            let name = String(data: nameSlice, encoding: .utf8)
                ?? String(data: nameSlice, encoding: .isoLatin1)
                ?? ""

            // Parse ZIP64 extra field (header ID 0x0001) when 32-bit fields overflow.
            if uncompressedSize == 0xFFFF_FFFF || compressedSize == 0xFFFF_FFFF || localHeaderOffset == 0xFFFF_FFFF {
                let extraStart = offset + 46 + nameLen
                let extraEnd = extraStart + extraLen
                var cursor = extraStart
                while cursor + 4 <= extraEnd {
                    let headerId = readUInt16LE(data, at: cursor)
                    let fieldLen = Int(readUInt16LE(data, at: cursor + 2))
                    cursor += 4
                    guard cursor + fieldLen <= extraEnd else { break }
                    if headerId == 0x0001 {
                        var fieldPos = cursor
                        if uncompressedSize == 0xFFFF_FFFF, fieldPos + 8 <= cursor + fieldLen {
                            uncompressedSize = readUInt64LE(data, at: fieldPos)
                            fieldPos += 8
                        }
                        if compressedSize == 0xFFFF_FFFF, fieldPos + 8 <= cursor + fieldLen {
                            compressedSize = readUInt64LE(data, at: fieldPos)
                            fieldPos += 8
                        }
                        if localHeaderOffset == 0xFFFF_FFFF, fieldPos + 8 <= cursor + fieldLen {
                            localHeaderOffset = readUInt64LE(data, at: fieldPos)
                        }
                        break
                    }
                    cursor += fieldLen
                }
            }

            entries.append(
                ZipCentralDirectoryEntry(
                    name: name,
                    compressionMethod: method,
                    crc32: crc32,
                    compressedSize: compressedSize,
                    uncompressedSize: uncompressedSize,
                    localHeaderOffset: localHeaderOffset
                )
            )
            offset = recordEnd
        }

        return entries
    }

    static func readUInt16LE(_ data: Data, at offset: Int) -> UInt16 {
        let base = data.startIndex + offset
        return UInt16(data[base]) | (UInt16(data[base + 1]) << 8)
    }

    static func readUInt32LE(_ data: Data, at offset: Int) -> UInt32 {
        let base = data.startIndex + offset
        return UInt32(data[base])
            | (UInt32(data[base + 1]) << 8)
            | (UInt32(data[base + 2]) << 16)
            | (UInt32(data[base + 3]) << 24)
    }

    static func readUInt64LE(_ data: Data, at offset: Int) -> UInt64 {
        let lo = UInt64(readUInt32LE(data, at: offset))
        let hi = UInt64(readUInt32LE(data, at: offset + 4))
        return lo | (hi << 32)
    }
}
