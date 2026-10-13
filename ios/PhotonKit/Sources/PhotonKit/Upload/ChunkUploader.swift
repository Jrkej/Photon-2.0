import Foundation

/// One `PUT /v1/uploads/{id}/chunks/{n}`.
public struct ChunkUpload: Sendable, Equatable {
    public let uploadID: String
    public let index: Int
    public let url: URL
    /// The upload token from announce (`Authorization: Bearer`).
    public let token: String
    /// `X-Chunk-CRC32C`: base64 of the big-endian CRC32C of the chunk.
    public let crc32cHeader: String
    /// The chunk's bytes, already sliced into their own file.
    public let fileURL: URL
    public let length: Int

    public init(uploadID: String, index: Int, url: URL, token: String, crc32cHeader: String, fileURL: URL, length: Int) {
        self.uploadID = uploadID
        self.index = index
        self.url = url
        self.token = token
        self.crc32cHeader = crc32cHeader
        self.fileURL = fileURL
        self.length = length
    }

    /// The request every uploader sends.
    public var httpRequest: HTTPRequest {
        HTTPRequest(method: "PUT", url: url, headers: [
            "Authorization": "Bearer \(token)",
            "X-Chunk-CRC32C": crc32cHeader,
            "Content-Type": "application/octet-stream",
            "Accept": protobufContentType,
        ], body: .file(fileURL), timeout: 120, preferHTTP3: true)
    }

    /// Identifies the transfer across launches (a background URLSession task description).
    public var transferKey: String { "\(uploadID)/\(index)" }
}

/// Sends chunks. PhotonApp implements this with a background URLSession, so a transfer
/// keeps going while the app is suspended; this foreground version is the default and
/// what the tests use.
public protocol ChunkUploader: Sendable {
    /// Returns on 2xx. Throws `PhotonError.api` for an error answer, `PhotonError.transport`
    /// when no answer arrived.
    func upload(_ chunk: ChunkUpload) async throws
}

public struct HTTPChunkUploader: ChunkUploader {
    let transport: HTTPTransport

    public init(transport: HTTPTransport) {
        self.transport = transport
    }

    public func upload(_ chunk: ChunkUpload) async throws {
        let response = try await transport.send(chunk.httpRequest)
        guard response.isSuccess else { throw PhotonError.api(APIError(response: response)) }
    }
}
