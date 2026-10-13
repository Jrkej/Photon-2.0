import Foundation
import XCTest
@testable import PhotonKit

final class CRC32CTests: XCTestCase {
    func testRFC3720Vectors() {
        XCTAssertEqual(CRC32C.checksum(Data(repeating: 0, count: 32)), 0x8A91_36AA)
        XCTAssertEqual(CRC32C.checksum(Data(repeating: 0xFF, count: 32)), 0x62A8_AB43)
        XCTAssertEqual(CRC32C.checksum(Data((0..<32).map { UInt8($0) })), 0x46DD_794E)
        XCTAssertEqual(CRC32C.checksum(Data((0..<32).reversed().map { UInt8($0) })), 0x113F_DB5C)
    }

    func testCheckValue() {
        XCTAssertEqual(CRC32C.checksum(Data("123456789".utf8)), 0xE306_9283)
        XCTAssertEqual(CRC32C.checksum(Data()), 0)
    }

    func testIncrementalEqualsOneShotAtEveryAlignment() {
        let data = testBytes(1000)
        let whole = CRC32C.checksum(data)
        for split in [0, 1, 3, 7, 8, 9, 63, 500, 999, 1000] {
            var c = CRC32C()
            c.update(data.prefix(split))
            c.update(data.suffix(from: split))
            XCTAssertEqual(c.value, whole, "split at \(split)")
        }
        // Unaligned start inside a larger buffer.
        let shifted = Data([1, 2, 3]) + data
        shifted.withUnsafeBytes { raw in
            var c = CRC32C()
            c.update(UnsafeRawBufferPointer(rebasing: raw[3...]))
            XCTAssertEqual(c.value, whole)
        }
    }

    func testHeaderIsBase64OfBigEndian() {
        XCTAssertEqual(CRC32C.headerValue(0xE306_9283), "4waSgw==")
        XCTAssertEqual(Data(base64Encoded: CRC32C.headerValue(0x0102_0304)), Data([1, 2, 3, 4]))
    }
}

final class SHA256Tests: XCTestCase {
    func testNISTVectors() {
        XCTAssertEqual(SHA256Hasher.hash(Data()).hexString,
                       "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        XCTAssertEqual(SHA256Hasher.hash(Data("abc".utf8)).hexString,
                       "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        XCTAssertEqual(SHA256Hasher.hash(Data("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".utf8)).hexString,
                       "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1")
    }

    func testMillionA() {
        var h = SHA256Hasher()
        let block = Data(repeating: UInt8(ascii: "a"), count: 1000)
        for _ in 0..<1000 { h.update(block) }
        XCTAssertEqual(h.finalize().hexString, "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0")
    }

    func testPaddingBoundaries() {
        // Lengths around the 55/56/64-byte padding edges, split arbitrarily.
        for n in [55, 56, 57, 63, 64, 65, 119, 120, 128] {
            let data = testBytes(n)
            var h = SHA256Hasher()
            h.update(data.prefix(n / 3))
            h.update(data.suffix(from: n / 3))
            XCTAssertEqual(h.finalize(), SHA256Hasher.hash(data), "length \(n)")
        }
        XCTAssertEqual(SHA256Hasher.hash(Data(repeating: 0x61, count: 64)).hexString,
                       "ffe054fe7ae0cb6dc65c3af9b61d5209f439851db43d0ba5997337df154668eb")
    }

    func testHashFile() throws {
        let dir = tempDirectory()
        let url = dir.appendingPathComponent("f")
        let data = testBytes(3 * 1024 * 1024 + 17)
        try data.write(to: url)
        let (digest, size) = try SHA256Hasher.hashFile(at: url)
        XCTAssertEqual(size, Int64(data.count))
        XCTAssertEqual(digest, SHA256Hasher.hash(data))
    }
}

final class PKCETests: XCTestCase {
    func testRFC7636AppendixB() {
        let pkce = PKCE(verifier: "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk")
        XCTAssertEqual(pkce.challenge, "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM")
    }

    func testGeneratedValuesAreBase64URLWithoutPadding() {
        let allowed = Set("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        var seen = Set<String>()
        for _ in 0..<20 {
            let p = PKCE.generate()
            XCTAssertEqual(p.verifier.count, 43)
            XCTAssertEqual(p.challenge.count, 43)
            XCTAssertTrue(p.verifier.allSatisfy(allowed.contains))
            XCTAssertTrue(p.challenge.allSatisfy(allowed.contains))
            XCTAssertEqual(p.challenge, PKCE.challenge(for: p.verifier))
            seen.insert(p.verifier)
        }
        XCTAssertEqual(seen.count, 20)
    }
}

final class EncodingTests: XCTestCase {
    func testHexRoundTrip() {
        let d = Data([0x00, 0x0f, 0xa5, 0xff])
        XCTAssertEqual(d.hexString, "000fa5ff")
        XCTAssertEqual(Data(hexString: "000FA5ff"), d)
        XCTAssertNil(Data(hexString: "abc"))
        XCTAssertNil(Data(hexString: "zz"))
    }

    func testBase64URL() {
        XCTAssertEqual(Data([0xfb, 0xff, 0xfe]).base64URLEncodedString, "-__-")
        XCTAssertEqual(Data([0x61]).base64URLEncodedString, "YQ")
    }

    func testBackoffStaysWithinBounds() {
        let low = Backoff(baseMs: 1000, capMs: 60_000, random: { 0 })
        let high = Backoff(baseMs: 1000, capMs: 60_000, random: { 0.999_999 })
        XCTAssertEqual(low.delayMs(attempt: 0), 500)
        XCTAssertEqual(low.delayMs(attempt: 3), 4000)
        XCTAssertEqual(high.delayMs(attempt: 3), 7999)
        XCTAssertEqual(low.delayMs(attempt: 50), 30_000)
        XCTAssertEqual(low.delayMs(attempt: 2, retryAfterSeconds: 7), 7000)
    }
}
