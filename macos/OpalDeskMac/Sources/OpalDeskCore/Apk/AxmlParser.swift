import Foundation

/// Errors thrown while decoding Android Binary XML (AXML) payloads.
public enum AxmlParserError: Error, LocalizedError, Sendable, Equatable {
    case emptyData
    case inputTooLarge(byteCount: Int, limit: Int)
    case invalidHeader(magic: UInt16)
    case truncatedChunk(offset: Int)
    case invalidChunkSize(size: Int, offset: Int)
    case tooManyStrings(count: Int, limit: Int)
    case tooManyChunks(limit: Int)
    case missingManifestElement

    public var errorDescription: String? {
        switch self {
        case .emptyData:
            return "AndroidManifest.xml 内容为空"
        case let .inputTooLarge(byteCount, limit):
            return "AndroidManifest.xml 体积过大（\(byteCount) 字节，上限 \(limit) 字节）"
        case let .invalidHeader(magic):
            return String(format: "无效的 AXML 文件头（0x%04X）", magic)
        case let .truncatedChunk(offset):
            return "AXML 数据在偏移 \(offset) 处被截断"
        case let .invalidChunkSize(size, offset):
            return "AXML Chunk 长度异常（size=\(size), offset=\(offset)）"
        case let .tooManyStrings(count, limit):
            return "AXML 字符串池条目过多（\(count)，上限 \(limit)）"
        case let .tooManyChunks(limit):
            return "AXML Chunk 数量超出上限（\(limit)）"
        case .missingManifestElement:
            return "AndroidManifest.xml 缺少 <manifest> 根节点"
        }
    }
}

/// A decoded attribute attached to an AXML start element.
public struct AxmlAttribute: Sendable, Equatable {
    public let namespace: String?
    public let name: String
    public let resourceId: UInt32?
    public let valueType: UInt8
    public let rawStringValue: String?
    public let rawData: UInt32

    public init(
        namespace: String?,
        name: String,
        resourceId: UInt32?,
        valueType: UInt8,
        rawStringValue: String?,
        rawData: UInt32
    ) {
        self.namespace = namespace
        self.name = name
        self.resourceId = resourceId
        self.valueType = valueType
        self.rawStringValue = rawStringValue
        self.rawData = rawData
    }

    /// Returns a human-usable string representation if the attribute holds a string or integer value.
    public var stringValue: String? {
        if let rawStringValue, !rawStringValue.isEmpty {
            return rawStringValue
        }
        switch valueType {
        case AxmlParser.typeIntDec:
            return String(Int32(bitPattern: rawData))
        case AxmlParser.typeIntHex:
            return String(format: "0x%x", rawData)
        case AxmlParser.typeIntBoolean:
            return rawData != 0 ? "true" : "false"
        default:
            return nil
        }
    }

    /// Returns an integer value when the attribute is numeric or a numeric string.
    public var intValue: Int? {
        switch valueType {
        case AxmlParser.typeIntDec:
            return Int(Int32(bitPattern: rawData))
        case AxmlParser.typeIntHex:
            return Int(rawData)
        case AxmlParser.typeString:
            return rawStringValue.flatMap { Int($0.trimmingCharacters(in: .whitespacesAndNewlines)) }
        default:
            if let rawStringValue {
                return Int(rawStringValue.trimmingCharacters(in: .whitespacesAndNewlines))
            }
            return nil
        }
    }

    /// Returns a boolean value when the attribute is a boolean or boolean string.
    public var boolValue: Bool? {
        switch valueType {
        case AxmlParser.typeIntBoolean:
            return rawData != 0
        case AxmlParser.typeIntDec, AxmlParser.typeIntHex:
            return rawData != 0
        default:
            guard let raw = rawStringValue?.lowercased() else { return nil }
            if raw == "true" || raw == "1" { return true }
            if raw == "false" || raw == "0" { return false }
            return nil
        }
    }

    /// Returns the Android resource ID if the attribute is a resource reference (`TYPE_REFERENCE`).
    public var referenceId: UInt32? {
        valueType == AxmlParser.typeReference ? rawData : nil
    }
}

/// A decoded element from an Android Binary XML stream.
public struct AxmlElement: Sendable, Equatable {
    public let name: String
    public let depth: Int
    public let attributes: [AxmlAttribute]

    public init(name: String, depth: Int, attributes: [AxmlAttribute]) {
        self.name = name
        self.depth = depth
        self.attributes = attributes
    }

    public func attribute(named name: String, resourceId: UInt32? = nil) -> AxmlAttribute? {
        if let resourceId, let byId = attributes.first(where: { $0.resourceId == resourceId }) {
            return byId
        }
        return attributes.first(where: { $0.name == name })
    }
}

/// Structured manifest fields extracted from an APK's compiled `AndroidManifest.xml`.
public struct AndroidManifestInfo: Sendable, Equatable {
    public let packageName: String
    public let versionName: String?
    public let versionCode: Int64?
    public let minSdkVersion: Int?
    public let targetSdkVersion: Int?
    public let applicationLabel: String?
    public let applicationLabelResId: UInt32?
    public let applicationIconPath: String?
    public let applicationIconResId: UInt32?
    public let permissions: [String]
    public let splitName: String?
    public let isSplitRequired: Bool

    public init(
        packageName: String,
        versionName: String? = nil,
        versionCode: Int64? = nil,
        minSdkVersion: Int? = nil,
        targetSdkVersion: Int? = nil,
        applicationLabel: String? = nil,
        applicationLabelResId: UInt32? = nil,
        applicationIconPath: String? = nil,
        applicationIconResId: UInt32? = nil,
        permissions: [String] = [],
        splitName: String? = nil,
        isSplitRequired: Bool = false
    ) {
        self.packageName = packageName
        self.versionName = versionName
        self.versionCode = versionCode
        self.minSdkVersion = minSdkVersion
        self.targetSdkVersion = targetSdkVersion
        self.applicationLabel = applicationLabel
        self.applicationLabelResId = applicationLabelResId
        self.applicationIconPath = applicationIconPath
        self.applicationIconResId = applicationIconResId
        self.permissions = permissions
        self.splitName = splitName
        self.isSplitRequired = isSplitRequired
    }
}

/// Pure Swift parser for Android Binary XML (AXML / `ResXMLTree`) used in `AndroidManifest.xml`.
public enum AxmlParser {
    // AOSP ResChunk_header types
    public static let chunkTypeStringPool: UInt16 = 0x0001
    public static let chunkTypeXml: UInt16 = 0x0003
    public static let chunkTypeResourceMap: UInt16 = 0x0180
    public static let chunkTypeStartNamespace: UInt16 = 0x0100
    public static let chunkTypeEndNamespace: UInt16 = 0x0101
    public static let chunkTypeStartElement: UInt16 = 0x0102
    public static let chunkTypeEndElement: UInt16 = 0x0103
    public static let chunkTypeCdata: UInt16 = 0x0104

    // AOSP Res_value data types
    public static let typeReference: UInt8 = 0x01
    public static let typeString: UInt8 = 0x03
    public static let typeIntDec: UInt8 = 0x10
    public static let typeIntHex: UInt8 = 0x11
    public static let typeIntBoolean: UInt8 = 0x12

    // Standard Android attribute resource IDs (`android.R.attr.*`)
    public static let attrResLabel: UInt32 = 0x0101_0001
    public static let attrResIcon: UInt32 = 0x0101_0002
    public static let attrResName: UInt32 = 0x0101_0003
    public static let attrResMinSdkVersion: UInt32 = 0x0101_020C
    public static let attrResVersionCode: UInt32 = 0x0101_021B
    public static let attrResVersionName: UInt32 = 0x0101_021C
    public static let attrResTargetSdkVersion: UInt32 = 0x0101_0270
    public static let attrResVersionCodeMajor: UInt32 = 0x0101_0576

    private static let stringPoolUtf8Flag: UInt32 = 1 << 8
    private static let maxInputBytes = 16 * 1024 * 1024
    private static let maxStringCount = 200_000
    private static let maxChunkIterations = 100_000

    /// Parses all XML elements from an AXML byte buffer.
    public static func parseElements(from data: Data) throws -> [AxmlElement] {
        guard !data.isEmpty else {
            throw AxmlParserError.emptyData
        }
        guard data.count <= maxInputBytes else {
            throw AxmlParserError.inputTooLarge(byteCount: data.count, limit: maxInputBytes)
        }
        guard data.count >= 8 else {
            throw AxmlParserError.truncatedChunk(offset: 0)
        }

        let reader = LittleEndianReader(data: data)
        let rootType = try reader.readUInt16(at: 0)
        let rootHeaderSize = Int(try reader.readUInt16(at: 2))
        let rootSize = Int(try reader.readUInt32(at: 4))

        guard rootType == chunkTypeXml else {
            throw AxmlParserError.invalidHeader(magic: rootType)
        }
        guard rootHeaderSize >= 8, rootHeaderSize <= data.count else {
            throw AxmlParserError.invalidChunkSize(size: rootHeaderSize, offset: 0)
        }

        let effectiveEnd = min(data.count, max(rootHeaderSize, rootSize))
        var offset = rootHeaderSize
        var stringPool: [String] = []
        var resourceMap: [UInt32] = []
        var elements: [AxmlElement] = []
        var depth = 0
        var chunkCount = 0

        while offset + 8 <= effectiveEnd {
            chunkCount += 1
            if chunkCount > maxChunkIterations {
                throw AxmlParserError.tooManyChunks(limit: maxChunkIterations)
            }

            let chunkType = try reader.readUInt16(at: offset)
            let headerSize = Int(try reader.readUInt16(at: offset + 2))
            let chunkSize = Int(try reader.readUInt32(at: offset + 4))

            guard chunkSize >= 8, headerSize >= 8, headerSize <= chunkSize else {
                throw AxmlParserError.invalidChunkSize(size: chunkSize, offset: offset)
            }
            guard offset + chunkSize <= effectiveEnd else {
                throw AxmlParserError.truncatedChunk(offset: offset)
            }

            switch chunkType {
            case chunkTypeStringPool:
                stringPool = try parseStringPool(
                    reader: reader,
                    chunkOffset: offset,
                    headerSize: headerSize,
                    chunkSize: chunkSize
                )

            case chunkTypeResourceMap:
                resourceMap = try parseResourceMap(
                    reader: reader,
                    chunkOffset: offset,
                    headerSize: headerSize,
                    chunkSize: chunkSize
                )

            case chunkTypeStartElement:
                let element = try parseStartElement(
                    reader: reader,
                    chunkOffset: offset,
                    headerSize: headerSize,
                    chunkSize: chunkSize,
                    depth: depth,
                    stringPool: stringPool,
                    resourceMap: resourceMap
                )
                elements.append(element)
                depth += 1

            case chunkTypeEndElement:
                if depth > 0 {
                    depth -= 1
                }

            default:
                // Namespaces, CDATA, or unknown vendor chunks are safely skipped.
                break
            }

            offset += chunkSize
        }

        return elements
    }

    /// Parses an `AndroidManifest.xml` binary buffer into structured `AndroidManifestInfo`.
    public static func parseManifest(from data: Data) throws -> AndroidManifestInfo {
        let elements = try parseElements(from: data)
        guard let manifestEl = elements.first(where: { $0.name == "manifest" }) else {
            throw AxmlParserError.missingManifestElement
        }

        let packageName = manifestEl.attribute(named: "package")?.stringValue?
            .trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
        let versionName = manifestEl.attribute(named: "versionName", resourceId: attrResVersionName)?.stringValue
        let versionCodeLow = manifestEl.attribute(named: "versionCode", resourceId: attrResVersionCode)?.intValue
        let versionCodeMajor = manifestEl.attribute(named: "versionCodeMajor", resourceId: attrResVersionCodeMajor)?.intValue

        let versionCode: Int64? = {
            guard let low = versionCodeLow else { return nil }
            if let major = versionCodeMajor {
                return (Int64(major) << 32) | Int64(UInt32(bitPattern: Int32(truncatingIfNeeded: low)))
            }
            return Int64(low)
        }()

        let splitName = manifestEl.attribute(named: "split")?.stringValue
        let isSplitRequired = manifestEl.attribute(named: "isSplitRequired")?.boolValue ?? false

        var minSdkVersion: Int?
        var targetSdkVersion: Int?
        var appLabel: String?
        var appLabelResId: UInt32?
        var appIconPath: String?
        var appIconResId: UInt32?
        var permissions: [String] = []
        var seenPermissions = Set<String>()

        for element in elements {
            switch element.name {
            case "uses-sdk":
                if minSdkVersion == nil {
                    minSdkVersion = element.attribute(named: "minSdkVersion", resourceId: attrResMinSdkVersion)?.intValue
                }
                if targetSdkVersion == nil {
                    targetSdkVersion = element.attribute(named: "targetSdkVersion", resourceId: attrResTargetSdkVersion)?.intValue
                }

            case "uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m":
                if let perm = element.attribute(named: "name", resourceId: attrResName)?.stringValue?
                    .trimmingCharacters(in: .whitespacesAndNewlines),
                    !perm.isEmpty,
                    seenPermissions.insert(perm).inserted
                {
                    permissions.append(perm)
                }

            case "application":
                if let labelAttr = element.attribute(named: "label", resourceId: attrResLabel) {
                    if let refId = labelAttr.referenceId {
                        appLabelResId = refId
                    } else if let str = labelAttr.stringValue, !str.isEmpty {
                        appLabel = str
                    }
                }
                if let iconAttr = element.attribute(named: "icon", resourceId: attrResIcon) {
                    if let refId = iconAttr.referenceId {
                        appIconResId = refId
                    } else if let str = iconAttr.stringValue, !str.isEmpty {
                        appIconPath = str
                    }
                }

            default:
                break
            }
        }

        return AndroidManifestInfo(
            packageName: packageName,
            versionName: versionName,
            versionCode: versionCode,
            minSdkVersion: minSdkVersion,
            targetSdkVersion: targetSdkVersion,
            applicationLabel: appLabel,
            applicationLabelResId: appLabelResId,
            applicationIconPath: appIconPath,
            applicationIconResId: appIconResId,
            permissions: permissions,
            splitName: splitName,
            isSplitRequired: isSplitRequired
        )
    }

    // MARK: - Private Chunk Decoders

    private static func parseStringPool(
        reader: LittleEndianReader,
        chunkOffset: Int,
        headerSize: Int,
        chunkSize: Int
    ) throws -> [String] {
        guard headerSize >= 28 else {
            throw AxmlParserError.invalidChunkSize(size: headerSize, offset: chunkOffset)
        }

        let stringCount = Int(try reader.readUInt32(at: chunkOffset + 8))
        guard stringCount <= maxStringCount else {
            throw AxmlParserError.tooManyStrings(count: stringCount, limit: maxStringCount)
        }

        let flags = try reader.readUInt32(at: chunkOffset + 16)
        let stringsStart = Int(try reader.readUInt32(at: chunkOffset + 20))
        let isUtf8 = (flags & stringPoolUtf8Flag) != 0

        let offsetsTableStart = chunkOffset + headerSize
        let stringsDataStart = chunkOffset + stringsStart
        let chunkEnd = chunkOffset + chunkSize

        guard offsetsTableStart + stringCount * 4 <= chunkEnd else {
            throw AxmlParserError.truncatedChunk(offset: offsetsTableStart)
        }

        var result: [String] = []
        result.reserveCapacity(stringCount)

        for i in 0..<stringCount {
            let relOffset = Int(try reader.readUInt32(at: offsetsTableStart + i * 4))
            let strPos = stringsDataStart + relOffset
            guard strPos >= stringsDataStart, strPos < chunkEnd else {
                result.append("")
                continue
            }

            if isUtf8 {
                result.append(try readUtf8PoolString(reader: reader, at: strPos, limit: chunkEnd))
            } else {
                result.append(try readUtf16PoolString(reader: reader, at: strPos, limit: chunkEnd))
            }
        }

        return result
    }

    private static func readUtf8PoolString(
        reader: LittleEndianReader,
        at start: Int,
        limit: Int
    ) throws -> String {
        var cursor = start
        // 1. UTF-16 character length (1 or 2 bytes) — skip
        guard cursor < limit else { return "" }
        let charLen0 = try reader.readUInt8(at: cursor)
        cursor += (charLen0 & 0x80) != 0 ? 2 : 1

        // 2. UTF-8 byte length (1 or 2 bytes)
        guard cursor < limit else { return "" }
        let byteLen0 = try reader.readUInt8(at: cursor)
        let byteLen: Int
        if (byteLen0 & 0x80) != 0 {
            guard cursor + 1 < limit else { return "" }
            let byteLen1 = try reader.readUInt8(at: cursor + 1)
            byteLen = (Int(byteLen0 & 0x7F) << 8) | Int(byteLen1)
            cursor += 2
        } else {
            byteLen = Int(byteLen0)
            cursor += 1
        }

        guard byteLen >= 0, cursor + byteLen <= limit else {
            return ""
        }
        let slice = reader.slice(from: cursor, length: byteLen)
        return String(decoding: slice, as: UTF8.self)
    }

    private static func readUtf16PoolString(
        reader: LittleEndianReader,
        at start: Int,
        limit: Int
    ) throws -> String {
        var cursor = start
        guard cursor + 2 <= limit else { return "" }
        let len0 = try reader.readUInt16(at: cursor)
        let charCount: Int
        if (len0 & 0x8000) != 0 {
            guard cursor + 4 <= limit else { return "" }
            let len1 = try reader.readUInt16(at: cursor + 2)
            charCount = (Int(len0 & 0x7FFF) << 16) | Int(len1)
            cursor += 4
        } else {
            charCount = Int(len0)
            cursor += 2
        }

        let byteLen = charCount * 2
        guard charCount >= 0, cursor + byteLen <= limit else {
            return ""
        }
        var codeUnits: [UInt16] = []
        codeUnits.reserveCapacity(charCount)
        for i in 0..<charCount {
            codeUnits.append(try reader.readUInt16(at: cursor + i * 2))
        }
        return String(decoding: codeUnits, as: UTF16.self)
    }

    private static func parseResourceMap(
        reader: LittleEndianReader,
        chunkOffset: Int,
        headerSize: Int,
        chunkSize: Int
    ) throws -> [UInt32] {
        let bodySize = chunkSize - headerSize
        let count = bodySize / 4
        var map: [UInt32] = []
        map.reserveCapacity(count)
        let start = chunkOffset + headerSize
        for i in 0..<count {
            map.append(try reader.readUInt32(at: start + i * 4))
        }
        return map
    }

    private static func parseStartElement(
        reader: LittleEndianReader,
        chunkOffset: Int,
        headerSize: Int,
        chunkSize: Int,
        depth: Int,
        stringPool: [String],
        resourceMap: [UInt32]
    ) throws -> AxmlElement {
        let extStart = chunkOffset + headerSize
        let chunkEnd = chunkOffset + chunkSize
        guard extStart + 20 <= chunkEnd else {
            throw AxmlParserError.truncatedChunk(offset: extStart)
        }

        // ResXMLTree_attrExt: ns(4), name(4), attributeStart(2), attributeSize(2), attributeCount(2), idIndex(2), classIndex(2), styleIndex(2)
        let nameIndex = Int(try reader.readInt32(at: extStart + 4))
        let attrStart = Int(try reader.readUInt16(at: extStart + 8))
        let attrSize = Int(try reader.readUInt16(at: extStart + 10))
        let attrCount = Int(try reader.readUInt16(at: extStart + 12))

        let elementName = poolString(stringPool, index: nameIndex) ?? ""
        var attributes: [AxmlAttribute] = []
        attributes.reserveCapacity(attrCount)

        if attrCount > 0, attrSize >= 20 {
            let firstAttrOffset = extStart + attrStart
            for i in 0..<attrCount {
                let attrOffset = firstAttrOffset + i * attrSize
                guard attrOffset + 20 <= chunkEnd else { break }

                let attrNsIdx = Int(try reader.readInt32(at: attrOffset))
                let attrNameIdx = Int(try reader.readInt32(at: attrOffset + 4))
                let rawValueIdx = Int(try reader.readInt32(at: attrOffset + 8))
                let valueType = try reader.readUInt8(at: attrOffset + 15)
                let rawData = try reader.readUInt32(at: attrOffset + 16)

                let ns = poolString(stringPool, index: attrNsIdx)
                let attrName = poolString(stringPool, index: attrNameIdx) ?? ""
                let resId = (attrNameIdx >= 0 && attrNameIdx < resourceMap.count) ? resourceMap[attrNameIdx] : nil

                var resolvedString = poolString(stringPool, index: rawValueIdx)
                if resolvedString == nil, valueType == typeString {
                    resolvedString = poolString(stringPool, index: Int(Int32(bitPattern: rawData)))
                }

                attributes.append(
                    AxmlAttribute(
                        namespace: ns,
                        name: attrName,
                        resourceId: resId,
                        valueType: valueType,
                        rawStringValue: resolvedString,
                        rawData: rawData
                    )
                )
            }
        }

        return AxmlElement(name: elementName, depth: depth, attributes: attributes)
    }

    private static func poolString(_ pool: [String], index: Int) -> String? {
        guard index >= 0, index < pool.count else { return nil }
        return pool[index]
    }
}

/// Bounds-checked little-endian binary reader over `Data`.
internal struct LittleEndianReader: Sendable {
    let data: Data

    func readUInt8(at offset: Int) throws -> UInt8 {
        guard offset >= 0, offset < data.count else {
            throw AxmlParserError.truncatedChunk(offset: offset)
        }
        return data[data.startIndex + offset]
    }

    func readUInt16(at offset: Int) throws -> UInt16 {
        guard offset >= 0, offset + 2 <= data.count else {
            throw AxmlParserError.truncatedChunk(offset: offset)
        }
        let base = data.startIndex + offset
        return UInt16(data[base]) | (UInt16(data[base + 1]) << 8)
    }

    func readUInt32(at offset: Int) throws -> UInt32 {
        guard offset >= 0, offset + 4 <= data.count else {
            throw AxmlParserError.truncatedChunk(offset: offset)
        }
        let base = data.startIndex + offset
        return UInt32(data[base])
            | (UInt32(data[base + 1]) << 8)
            | (UInt32(data[base + 2]) << 16)
            | (UInt32(data[base + 3]) << 24)
    }

    func readInt32(at offset: Int) throws -> Int32 {
        Int32(bitPattern: try readUInt32(at: offset))
    }

    func slice(from offset: Int, length: Int) -> Data {
        let start = data.startIndex + offset
        return data[start..<(start + length)]
    }
}
