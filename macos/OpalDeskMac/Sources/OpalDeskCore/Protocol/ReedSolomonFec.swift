import Foundation

/// Errors thrown by `ReedSolomonFec` encoding and erasure reconstruction.
public enum ReedSolomonFecError: Error, Sendable, Equatable, LocalizedError {
    case invalidShardCounts(data: Int, parity: Int)
    case mismatchedShardSizes
    case insufficientShards(received: Int, required: Int)
    case singularMatrix

    public var errorDescription: String? {
        switch self {
        case let .invalidShardCounts(data, parity):
            return "Invalid RS shard counts: data=\(data), parity=\(parity)"
        case .mismatchedShardSizes:
            return "All Reed-Solomon shards must share the same byte length"
        case let .insufficientShards(received, required):
            return "Insufficient shards for RS recovery: received \(received), need \(required)"
        case .singularMatrix:
            return "Reed-Solomon decoding matrix is singular"
        }
    }
}

/// Pure Swift 6 `Sendable` Reed-Solomon erasure codec over GF(2^8) with
/// primitive polynomial `0x11D` (x^8 + x^4 + x^3 + x^2 + 1).
/// Uses the exact systematic Cauchy generator matrix (`inv(parityRow ^ (parityCount + dataCol))`)
/// aligned with `:bridge` `ReedSolomonFec.java` for 100% wire interoperability.
public struct ReedSolomonFec: Sendable {
    public static let primitivePolynomial: Int = 0x11D
    public static let maxTotalShards: Int = 255

    private static let gfTables: (exp: [UInt8], log: [UInt8]) = {
        var exp = [UInt8](repeating: 0, count: 512)
        var log = [UInt8](repeating: 0, count: 256)
        var value = 1
        for i in 0..<255 {
            exp[i] = UInt8(value)
            log[value] = UInt8(i)
            value <<= 1
            if (value & 0x100) != 0 {
                value ^= primitivePolynomial
            }
        }
        for i in 255..<512 {
            exp[i] = exp[i - 255]
        }
        return (exp, log)
    }()

    public let dataShards: Int
    public let parityShards: Int
    public var totalShards: Int { dataShards + parityShards }

    /// Systematic Cauchy coding matrix of dimensions `(dataShards + parityShards) x dataShards`.
    private let codingMatrix: [[UInt8]]

    public init(dataShards: Int, parityShards: Int) throws {
        guard dataShards >= 1,
              parityShards >= 0,
              dataShards + parityShards <= Self.maxTotalShards
        else {
            throw ReedSolomonFecError.invalidShardCounts(data: dataShards, parity: parityShards)
        }
        self.dataShards = dataShards
        self.parityShards = parityShards
        self.codingMatrix = Self.buildSystematicCauchyMatrix(
            dataShards: dataShards,
            parityShards: parityShards
        )
    }

    // MARK: - GF(2^8) Arithmetic

    @inline(__always)
    public static func gfMul(_ a: UInt8, _ b: UInt8) -> UInt8 {
        if a == 0 || b == 0 { return 0 }
        let logA = Int(gfTables.log[Int(a)])
        let logB = Int(gfTables.log[Int(b)])
        return gfTables.exp[logA + logB]
    }

    @inline(__always)
    public static func gfDiv(_ a: UInt8, _ b: UInt8) -> UInt8 {
        precondition(b != 0, "GF(2^8) division by zero")
        if a == 0 { return 0 }
        let logA = Int(gfTables.log[Int(a)])
        let logB = Int(gfTables.log[Int(b)])
        return gfTables.exp[logA + 255 - logB]
    }

    @inline(__always)
    public static func gfInv(_ a: UInt8) -> UInt8 {
        precondition(a != 0, "GF(2^8) inverse of zero")
        return gfTables.exp[255 - Int(gfTables.log[Int(a)])]
    }

    @inline(__always)
    public static func gfPow(_ a: UInt8, _ exp: Int) -> UInt8 {
        if exp == 0 { return 1 }
        if a == 0 { return 0 }
        let logA = Int(gfTables.log[Int(a)])
        return gfTables.exp[(logA * exp) % 255]
    }

    @inline(__always)
    public static func cauchyCoeff(parityRow: Int, dataCol: Int, parityCount: Int) -> UInt8 {
        gfInv(UInt8(parityRow ^ (parityCount + dataCol)))
    }

    // MARK: - Encoding & Decoding

    /// Computes `parityShards` parity buffers for equal-length `data` shards.
    public func encodeParity(data: [Data]) throws -> [Data] {
        guard data.count == dataShards else {
            throw ReedSolomonFecError.invalidShardCounts(data: data.count, parity: parityShards)
        }
        guard let shardSize = data.first?.count else {
            throw ReedSolomonFecError.invalidShardCounts(data: 0, parity: parityShards)
        }
        for shard in data where shard.count != shardSize {
            throw ReedSolomonFecError.mismatchedShardSizes
        }
        guard parityShards > 0 else { return [] }

        let dataBytes = data.map { [UInt8]($0) }
        var parities: [Data] = []
        parities.reserveCapacity(parityShards)

        for p in 0..<parityShards {
            let row = codingMatrix[dataShards + p]
            var out = [UInt8](repeating: 0, count: shardSize)
            for d in 0..<dataShards {
                let coeff = row[d]
                if coeff == 0 { continue }
                Self.mulAccumulate(src: dataBytes[d], coeff: coeff, into: &out)
            }
            parities.append(Data(out))
        }
        return parities
    }

    /// Reconstructs all `dataShards` data buffers from any `dataShards` present shards
    /// out of `totalShards` slots (where `nil` marks a lost shard).
    public func reconstructDataShards(from shards: [Data?]) throws -> [Data] {
        guard shards.count == totalShards else {
            throw ReedSolomonFecError.invalidShardCounts(data: dataShards, parity: parityShards)
        }

        // Fast path: all data shards arrived intact -> zero GF math.
        var allDataPresent = true
        for i in 0..<dataShards where shards[i] == nil {
            allDataPresent = false
            break
        }
        if allDataPresent {
            return (0..<dataShards).compactMap { shards[$0] }
        }

        var presentIndices: [Int] = []
        var presentBytes: [[UInt8]] = []
        presentIndices.reserveCapacity(dataShards)
        presentBytes.reserveCapacity(dataShards)

        var shardSize: Int?
        for (idx, maybeShard) in shards.enumerated() {
            guard let shard = maybeShard else { continue }
            if let expected = shardSize {
                guard shard.count == expected else { throw ReedSolomonFecError.mismatchedShardSizes }
            } else {
                shardSize = shard.count
            }
            presentIndices.append(idx)
            presentBytes.append([UInt8](shard))
            if presentIndices.count == dataShards { break }
        }

        guard presentIndices.count == dataShards, let resolvedSize = shardSize else {
            throw ReedSolomonFecError.insufficientShards(received: presentIndices.count, required: dataShards)
        }

        let subMatrix = presentIndices.map { codingMatrix[$0] }
        let decodeMatrix = try Self.invertMatrix(subMatrix)

        var result: [Data] = []
        result.reserveCapacity(dataShards)
        for d in 0..<dataShards {
            if let existing = shards[d] {
                result.append(existing)
                continue
            }
            let row = decodeMatrix[d]
            var recovered = [UInt8](repeating: 0, count: resolvedSize)
            for c in 0..<dataShards {
                let coeff = row[c]
                if coeff == 0 { continue }
                Self.mulAccumulate(src: presentBytes[c], coeff: coeff, into: &recovered)
            }
            result.append(Data(recovered))
        }
        return result
    }

    // MARK: - Matrix Helpers

    private static func mulAccumulate(src: [UInt8], coeff: UInt8, into dst: inout [UInt8]) {
        if coeff == 1 {
            for i in 0..<src.count { dst[i] ^= src[i] }
            return
        }
        let logC = Int(gfTables.log[Int(coeff)])
        for i in 0..<src.count {
            let v = src[i]
            if v != 0 {
                dst[i] ^= gfTables.exp[logC + Int(gfTables.log[Int(v)])]
            }
        }
    }

    private static func buildSystematicCauchyMatrix(dataShards: Int, parityShards: Int) -> [[UInt8]] {
        let total = dataShards + parityShards
        var matrix = [[UInt8]](repeating: [UInt8](repeating: 0, count: dataShards), count: total)
        for i in 0..<dataShards {
            matrix[i][i] = 1
        }
        for p in 0..<parityShards {
            for j in 0..<dataShards {
                matrix[dataShards + p][j] = cauchyCoeff(parityRow: p, dataCol: j, parityCount: parityShards)
            }
        }
        return matrix
    }

    private static func invertMatrix(_ matrix: [[UInt8]]) throws -> [[UInt8]] {
        let n = matrix.count
        var aug = [[UInt8]](repeating: [UInt8](repeating: 0, count: n * 2), count: n)
        for r in 0..<n {
            for c in 0..<n { aug[r][c] = matrix[r][c] }
            aug[r][n + r] = 1
        }

        for col in 0..<n {
            if aug[col][col] == 0 {
                var pivot = -1
                for r in (col + 1)..<n where aug[r][col] != 0 {
                    pivot = r
                    break
                }
                guard pivot >= 0 else { throw ReedSolomonFecError.singularMatrix }
                aug.swapAt(col, pivot)
            }
            let invDiag = gfInv(aug[col][col])
            if invDiag != 1 {
                for c in col..<(n * 2) { aug[col][c] = gfMul(aug[col][c], invDiag) }
            }
            for r in 0..<n where r != col {
                let factor = aug[r][col]
                if factor == 0 { continue }
                for c in col..<(n * 2) {
                    aug[r][c] ^= gfMul(factor, aug[col][c])
                }
            }
        }
        return (0..<n).map { Array(aug[$0][n..<(n * 2)]) }
    }
}
