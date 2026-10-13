import Foundation

/// CRC-32C (Castagnoli, reflected polynomial 0x82F63B78), the checksum the image server
/// checks on every chunk. Apple has no public API for it, so it is implemented here
/// (slice-by-8, about 1 GB/s in release builds).
public struct CRC32C: Sendable {
    private var state: UInt32 = 0xFFFF_FFFF

    public init() {}

    /// The checksum of everything passed to `update` so far.
    public var value: UInt32 { state ^ 0xFFFF_FFFF }

    public mutating func update(_ data: Data) {
        data.withUnsafeBytes { update($0) }
    }

    public mutating func update(_ buffer: UnsafeRawBufferPointer) {
        var crc = state
        var offset = 0
        let count = buffer.count
        Self.table.withUnsafeBufferPointer { t in
            while count - offset >= 8 {
                let one = UInt32(littleEndian: buffer.loadUnaligned(fromByteOffset: offset, as: UInt32.self)) ^ crc
                let two = UInt32(littleEndian: buffer.loadUnaligned(fromByteOffset: offset + 4, as: UInt32.self))
                crc = t[7 * 256 + Int(one & 0xff)]
                    ^ t[6 * 256 + Int((one >> 8) & 0xff)]
                    ^ t[5 * 256 + Int((one >> 16) & 0xff)]
                    ^ t[4 * 256 + Int(one >> 24)]
                    ^ t[3 * 256 + Int(two & 0xff)]
                    ^ t[2 * 256 + Int((two >> 8) & 0xff)]
                    ^ t[1 * 256 + Int((two >> 16) & 0xff)]
                    ^ t[Int(two >> 24)]
                offset += 8
            }
            while offset < count {
                crc = t[Int((crc ^ UInt32(buffer[offset])) & 0xff)] ^ (crc >> 8)
                offset += 1
            }
        }
        state = crc
    }

    public static func checksum(_ data: Data) -> UInt32 {
        var c = CRC32C()
        c.update(data)
        return c.value
    }

    /// The `X-Chunk-CRC32C` header value: base64 of the 4-byte big-endian checksum.
    public static func headerValue(_ crc: UInt32) -> String {
        let be = crc.bigEndian
        return withUnsafeBytes(of: be) { Data($0) }.base64EncodedString()
    }

    /// 8 tables of 256 entries, flattened. Table 0 is the classic byte-at-a-time table.
    static let table: [UInt32] = {
        let poly: UInt32 = 0x82F6_3B78
        var t = [UInt32](repeating: 0, count: 8 * 256)
        for i in 0..<256 {
            var c = UInt32(i)
            for _ in 0..<8 { c = (c & 1) != 0 ? (c >> 1) ^ poly : c >> 1 }
            t[i] = c
        }
        for k in 1..<8 {
            for i in 0..<256 {
                let prev = t[(k - 1) * 256 + i]
                t[k * 256 + i] = (prev >> 8) ^ t[Int(prev & 0xff)]
            }
        }
        return t
    }()
}
