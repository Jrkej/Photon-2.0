import Foundation
#if canImport(CryptoKit)
import CryptoKit
#endif

/// Streaming SHA-256. CryptoKit (hardware accelerated) on Apple platforms; a portable
/// implementation elsewhere, so the core builds and tests on Linux. Both are checked
/// against the same NIST vectors in the tests.
public struct SHA256Hasher: Sendable {
    #if canImport(CryptoKit)
    private var impl = CryptoKit.SHA256()
    #else
    private var impl = PortableSHA256()
    #endif

    public init() {}

    public mutating func update(_ data: Data) {
        data.withUnsafeBytes { update($0) }
    }

    public mutating func update(_ buffer: UnsafeRawBufferPointer) {
        #if canImport(CryptoKit)
        impl.update(bufferPointer: buffer)
        #else
        impl.update(buffer)
        #endif
    }

    /// The 32-byte digest.
    public func finalize() -> Data {
        #if canImport(CryptoKit)
        return Data(impl.finalize())
        #else
        var copy = impl
        return Data(copy.finalize())
        #endif
    }

    public static func hash(_ data: Data) -> Data {
        var h = SHA256Hasher()
        h.update(data)
        return h.finalize()
    }

    /// Hashes a file in 1 MiB blocks. Returns the digest and the byte count.
    public static func hashFile(at url: URL) throws -> (digest: Data, size: Int64) {
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var h = SHA256Hasher()
        var size: Int64 = 0
        while true {
            let block = try handle.read(upToCount: 1 << 20) ?? Data()
            if block.isEmpty { break }
            h.update(block)
            size += Int64(block.count)
        }
        return (h.finalize(), size)
    }
}

/// FIPS 180-4 SHA-256 in plain Swift. Used only where CryptoKit is unavailable.
struct PortableSHA256: Sendable {
    private var h: [UInt32] = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a,
        0x510e527f, 0x9b05688c, 0x1f83d9ab, 0x5be0cd19,
    ]
    private var pending: [UInt8] = []
    private var length: UInt64 = 0

    private static let k: [UInt32] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4, 0xab1c5ed5,
        0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe, 0x9bdc06a7, 0xc19bf174,
        0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
        0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7, 0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967,
        0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85,
        0xa2bfe8a1, 0xa81a664b, 0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070,
        0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7, 0xc67178f2,
    ]

    mutating func update(_ buffer: UnsafeRawBufferPointer) {
        length &+= UInt64(buffer.count)
        var offset = 0
        if !pending.isEmpty {
            let take = min(64 - pending.count, buffer.count)
            pending.append(contentsOf: buffer[0..<take])
            offset = take
            if pending.count == 64 {
                let block = pending
                block.withUnsafeBytes { compress($0, at: 0) }
                pending.removeAll(keepingCapacity: true)
            }
        }
        while buffer.count - offset >= 64 {
            compress(buffer, at: offset)
            offset += 64
        }
        if offset < buffer.count {
            pending.append(contentsOf: buffer[offset..<buffer.count])
        }
    }

    mutating func finalize() -> [UInt8] {
        let bitLength = length &* 8
        var tail: [UInt8] = [0x80]
        // Zeros so that pending + 0x80 + zeros ends 8 bytes short of a block boundary.
        let padLen = (55 - pending.count + 64) % 64
        tail.append(contentsOf: [UInt8](repeating: 0, count: padLen))
        for i in (0..<8).reversed() { tail.append(UInt8((bitLength >> (UInt64(i) * 8)) & 0xff)) }
        let savedLength = length
        tail.withUnsafeBytes { update($0) }
        length = savedLength
        precondition(pending.isEmpty)
        var out = [UInt8]()
        out.reserveCapacity(32)
        for word in h {
            out.append(UInt8(word >> 24)); out.append(UInt8((word >> 16) & 0xff))
            out.append(UInt8((word >> 8) & 0xff)); out.append(UInt8(word & 0xff))
        }
        return out
    }

    private mutating func compress(_ buffer: UnsafeRawBufferPointer, at offset: Int) {
        var w = [UInt32](repeating: 0, count: 64)
        for i in 0..<16 {
            w[i] = UInt32(bigEndian: buffer.loadUnaligned(fromByteOffset: offset + i * 4, as: UInt32.self))
        }
        for i in 16..<64 {
            let s0 = w[i - 15].rotr(7) ^ w[i - 15].rotr(18) ^ (w[i - 15] >> 3)
            let s1 = w[i - 2].rotr(17) ^ w[i - 2].rotr(19) ^ (w[i - 2] >> 10)
            w[i] = w[i - 16] &+ s0 &+ w[i - 7] &+ s1
        }
        var a = h[0], b = h[1], c = h[2], d = h[3], e = h[4], f = h[5], g = h[6], hh = h[7]
        for i in 0..<64 {
            let s1 = e.rotr(6) ^ e.rotr(11) ^ e.rotr(25)
            let ch = (e & f) ^ (~e & g)
            let t1 = hh &+ s1 &+ ch &+ Self.k[i] &+ w[i]
            let s0 = a.rotr(2) ^ a.rotr(13) ^ a.rotr(22)
            let maj = (a & b) ^ (a & c) ^ (b & c)
            let t2 = s0 &+ maj
            hh = g; g = f; f = e; e = d &+ t1; d = c; c = b; b = a; a = t1 &+ t2
        }
        h[0] &+= a; h[1] &+= b; h[2] &+= c; h[3] &+= d
        h[4] &+= e; h[5] &+= f; h[6] &+= g; h[7] &+= hh
    }
}

private extension UInt32 {
    func rotr(_ n: UInt32) -> UInt32 { (self >> n) | (self << (32 - n)) }
}
