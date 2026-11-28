import Foundation

// MARK: - APK Signature Verification (v1 JAR + v2/v3 APK Signing Block)

extension StreamingZipArchive {
    private static let apkSigBlockMagic = Data("APK Sig Block 42".utf8)

    /// Checks for the standard 16-byte `"APK Sig Block 42"` trailer immediately preceding the ZIP Central Directory.
    func hasApkSigningBlock() -> Bool {
        guard centralDirectoryOffset >= 32 else { return false }
        guard let footer = try? source.read(offset: centralDirectoryOffset - 24, length: 24),
              footer.count == 24
        else {
            return false
        }
        let magicSlice = footer.suffix(16)
        guard magicSlice == Self.apkSigBlockMagic else { return false }
        let blockSize = Self.readUInt64LE(footer, at: 0)
        // Full block occupies `blockSize + 8` bytes before CD and must fit within file bounds.
        return blockSize >= 24 && blockSize + 8 <= centralDirectoryOffset
    }
}

extension AndroidPackageInspector {
    static func detectSignature(in archive: StreamingZipArchive) -> AndroidSignatureInfo {
        var certFiles: [String] = []
        for entry in archive.entries where !entry.isDirectory {
            let upper = entry.name.uppercased()
            guard upper.hasPrefix("META-INF/"), !upper.dropFirst("META-INF/".count).contains("/") else {
                continue
            }
            if upper.hasSuffix(".RSA") || upper.hasSuffix(".DSA") || upper.hasSuffix(".EC") {
                certFiles.append(entry.name)
            }
        }
        let hasSigningBlock = archive.hasApkSigningBlock()
        return AndroidSignatureInfo(
            hasV1JarSignature: !certFiles.isEmpty,
            hasApkSigningBlock: hasSigningBlock,
            v1CertificateFiles: certFiles.sorted()
        )
    }
}
