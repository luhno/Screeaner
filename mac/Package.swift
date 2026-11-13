// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "Screeaner",
    platforms: [.macOS(.v13)],
    targets: [
        // Declarations for the private CGVirtualDisplay API that lives in CoreGraphics.
        .target(name: "CVirtualDisplay", path: "Sources/CVirtualDisplay"),
        .executableTarget(
            name: "Screeaner",
            dependencies: ["CVirtualDisplay"],
            path: "Sources/Screeaner"
        ),
    ]
)
