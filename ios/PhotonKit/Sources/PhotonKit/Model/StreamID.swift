import Foundation

/// A Redis stream id ("<ms>-<seq>"), the type of every `event_id` and cursor. Ids are
/// compared as (ms, seq) tuples, never as strings: "9-0" < "10-0".
public struct StreamID: Comparable, Hashable, Sendable, CustomStringConvertible {
    public let ms: UInt64
    public let seq: UInt64

    public static let zero = StreamID(ms: 0, seq: 0)

    public init(ms: UInt64, seq: UInt64) {
        self.ms = ms
        self.seq = seq
    }

    /// Parses "1700000000000-3". A bare "1700000000000" means sequence 0.
    public init?(_ string: String) {
        let parts = string.split(separator: "-", omittingEmptySubsequences: false)
        guard parts.count == 1 || parts.count == 2, let ms = UInt64(parts[0]) else { return nil }
        let seq: UInt64
        if parts.count == 2 {
            guard let s = UInt64(parts[1]) else { return nil }
            seq = s
        } else {
            seq = 0
        }
        self.init(ms: ms, seq: seq)
    }

    public var description: String { "\(ms)-\(seq)" }

    public static func < (lhs: StreamID, rhs: StreamID) -> Bool {
        (lhs.ms, lhs.seq) < (rhs.ms, rhs.seq)
    }
}
