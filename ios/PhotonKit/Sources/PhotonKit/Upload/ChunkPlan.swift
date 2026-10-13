import Foundation

/// How one upload is cut into chunks. The chunk size is chosen once per upload and stored
/// with the job, because the image server keeps chunks by index: changing the size
/// between runs would make the received indexes meaningless.
public struct ChunkPlan: Sendable, Equatable {
    public let fileSize: Int64
    public let chunkSize: Int

    public init(fileSize: Int64, chunkSize: Int) {
        precondition(chunkSize > 0)
        self.fileSize = fileSize
        self.chunkSize = chunkSize
    }

    public var count: Int {
        guard fileSize > 0 else { return 0 }
        return Int((fileSize + Int64(chunkSize) - 1) / Int64(chunkSize))
    }

    /// Byte range of chunk `index` in the file.
    public func range(of index: Int) -> Range<Int64> {
        let start = Int64(index) * Int64(chunkSize)
        return start..<min(fileSize, start + Int64(chunkSize))
    }

    /// Indexes the server does not have, given `UploadStatus.received_chunks`.
    public func missing(received: [UInt32]) -> [Int] {
        let have = Set(received.map(Int.init))
        return (0..<count).filter { !have.contains($0) }
    }

    /// Aims for about `targetChunkSeconds` per chunk at the measured upload rate, within
    /// [minChunkSize, maxChunkSize], in 64 KiB steps. Without a measurement it uses the
    /// announce hint (1 MiB from the server). Never more than `maxChunkCount` chunks.
    public static func chooseChunkSize(fileSize: Int64, measuredBytesPerSecond: Double?, hint: Int?,
                                       tuning: PhotonTuning = PhotonTuning()) -> Int {
        let step = 64 * 1024
        var size: Double
        if let rate = measuredBytesPerSecond, rate > 0 {
            size = rate * tuning.targetChunkSeconds
        } else if let hint, hint > 0 {
            size = Double(hint)
        } else {
            size = Double(1 << 20)
        }
        size = min(max(size, Double(tuning.minChunkSize)), Double(tuning.maxChunkSize))
        var chosen = max(tuning.minChunkSize, (Int(size) / step) * step)
        let needed = Int((fileSize + Int64(tuning.maxChunkCount) - 1) / Int64(tuning.maxChunkCount))
        if needed > chosen {
            chosen = min(tuning.maxChunkSize, ((needed + step - 1) / step) * step)
        }
        return chosen
    }
}

/// Upload rate, smoothed over recent chunks (exponentially weighted).
public struct ThroughputEstimator: Sendable {
    public private(set) var bytesPerSecond: Double?
    private let alpha = 0.3

    public init() {}

    public mutating func record(bytes: Int, milliseconds: Int64) {
        // Very short samples say more about buffering than about the link.
        guard bytes > 0, milliseconds >= 50 else { return }
        let sample = Double(bytes) / (Double(milliseconds) / 1000)
        if let current = bytesPerSecond {
            bytesPerSecond = alpha * sample + (1 - alpha) * current
        } else {
            bytesPerSecond = sample
        }
    }
}

/// Writes chunk files from the upload copy. A background URLSession can only upload from
/// a file, so each chunk is sliced into its own file first.
public enum ChunkFiles {
    /// Ensures `destination` holds chunk `range` of `source`; returns its CRC32C header
    /// value and length. An existing file of the right length is left alone (it may be
    /// the body of an in-flight background transfer; its content is the same slice).
    public static func prepare(source: URL, range: Range<Int64>, destination: URL) throws -> (crcHeader: String, length: Int) {
        let handle = try FileHandle(forReadingFrom: source)
        defer { try? handle.close() }
        try handle.seek(toOffset: UInt64(range.lowerBound))
        let data = try handle.read(upToCount: Int(range.count)) ?? Data()
        guard data.count == range.count else {
            throw PhotonError.invalidResponse("upload copy shorter than expected")
        }
        let fm = FileManager.default
        let existing = (try? fm.attributesOfItem(atPath: destination.path)[.size] as? NSNumber)?.intValue
        if existing != data.count {
            try fm.createDirectory(at: destination.deletingLastPathComponent(), withIntermediateDirectories: true)
            try data.write(to: destination, options: [.atomic])
        }
        return (CRC32C.headerValue(CRC32C.checksum(data)), data.count)
    }
}
