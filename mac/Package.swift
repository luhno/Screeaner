// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Screeaner",
    platforms: [.macOS(.v13)],
    targets: [
        // Declarations for the private CGVirtualDisplay API that lives in CoreGraphics.
        .target(name: "CVirtualDisplay", path: "Sources/CVirtualDisplay"),
        // Pure logic (wire format, FEC, rate control) kept apart so it can be unit tested.
        .target(name: "ScreeanerCore", path: "Sources/ScreeanerCore"),
        .executableTarget(
            name: "Screeaner",
            dependencies: ["CVirtualDisplay", "ScreeanerCore"],
            path: "Sources/Screeaner"
        ),
        .testTarget(
            name: "ScreeanerCoreTests",
            dependencies: ["ScreeanerCore"],
            path: "Tests/ScreeanerCoreTests"
        ),
    ]
)
