// swift-tools-version: 6.0
// PhotonKit: the platform-independent core of the PHOTON iOS client.
// No UIKit, Photos or AuthenticationServices here: those live in PhotonApp behind the
// protocols in Sources/PhotonKit/Platform. This package builds and tests on Linux too.
import PackageDescription

let package = Package(
    name: "PhotonKit",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [
        .library(name: "PhotonKit", targets: ["PhotonKit"]),
    ],
    dependencies: [
        // The generator (protoc-gen-swift) is built from this same checkout by
        // Scripts/generate-proto.sh, so generated code and runtime always match.
        .package(url: "https://github.com/apple/swift-protobuf.git", .upToNextMinor(from: "1.38.1")),
    ],
    targets: [
        .target(
            name: "PhotonKit",
            dependencies: [.product(name: "SwiftProtobuf", package: "swift-protobuf")],
            path: "Sources/PhotonKit"
        ),
        .testTarget(
            name: "PhotonKitTests",
            dependencies: ["PhotonKit"],
            path: "Tests/PhotonKitTests"
        ),
    ]
)
