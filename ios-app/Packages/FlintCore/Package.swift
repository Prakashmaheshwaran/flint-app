// swift-tools-version: 5.9
import PackageDescription

let package = Package(
    name: "FlintCore",
    platforms: [.iOS(.v16)],
    products: [
        .library(name: "FlintCore", targets: ["FlintCore"]),
    ],
    targets: [
        .target(name: "FlintCore"),
    ]
)
