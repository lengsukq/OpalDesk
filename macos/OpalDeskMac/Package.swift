// swift-tools-version: 6.0
import PackageDescription

let package = Package(
    name: "OpalDeskMac",
    platforms: [
        .macOS(.v14)
    ],
    products: [
        .library(
            name: "OpalDeskCore",
            targets: ["OpalDeskCore"]
        ),
        .executable(
            name: "OpalDesk",
            targets: ["OpalDeskMac"]
        )
    ],
    targets: [
        .target(
            name: "OpalDeskCore",
            path: "Sources/OpalDeskCore",
            linkerSettings: [
                .linkedFramework("Network"),
                .linkedFramework("Security"),
                .linkedFramework("SystemConfiguration"),
                .linkedFramework("VideoToolbox"),
                .linkedFramework("CoreMedia"),
                .linkedFramework("AVFoundation"),
                .linkedFramework("AudioToolbox"),
                .linkedFramework("CoreGraphics"),
                .linkedFramework("CoreImage"),
                .linkedFramework("ImageIO"),
                .linkedFramework("AppKit"),
                .linkedLibrary("z")
            ]
        ),
        .executableTarget(
            name: "OpalDeskMac",
            dependencies: ["OpalDeskCore"],
            path: "Sources/OpalDeskMac"
        ),
        .testTarget(
            name: "OpalDeskCoreTests",
            dependencies: ["OpalDeskCore"],
            path: "Tests/OpalDeskCoreTests"
        )
    ],
    swiftLanguageModes: [.v6]
)
