import Foundation
import XCTest

/// The iOS side of the streak renderer contract.
///
/// This lives in the iOS suite so it runs in iOS CI (which triggers on `ios-app/**`), while
/// the Android suite keeps the Android guards. It asserts the shipped renderer is the only
/// one and that every placement goes through the named constants.
final class StreakRendererContractTests: XCTestCase {

    private func repoRoot() -> URL? {
        var url = URL(fileURLWithPath: #filePath).deletingLastPathComponent()
        while url.path != "/" {
            if FileManager.default.fileExists(atPath: url.appendingPathComponent("ios-app").path) {
                return url
            }
            url.deleteLastPathComponent()
        }
        return nil
    }

    private func sources() throws -> [(name: String, text: String)] {
        let root = try XCTUnwrap(repoRoot(), "could not locate the repository root from #filePath")
        let app = root.appendingPathComponent("ios-app/Flint")
        let enumerator = try XCTUnwrap(FileManager.default.enumerator(at: app,
            includingPropertiesForKeys: nil, options: [.skipsHiddenFiles]))
        var found: [(String, String)] = []
        for case let url as URL in enumerator where url.pathExtension == "swift" {
            let text = try String(contentsOf: url, encoding: .utf8)
            found.append((url.lastPathComponent, text))
        }
        return found
    }

    private func declaring(_ fragment: String) throws -> [String] {
        try sources().filter { $0.text.contains(fragment) }.map(\.name).sorted()
    }

    func testOnlyOneFlameRendererExists() throws {
        XCTAssertEqual(try declaring("struct FlintStreakEmber: View"), ["FlintStreakEmber.swift"])
        XCTAssertTrue(try declaring("func drawEmber(").isEmpty,
                      "the retired vector renderer must not return")
        XCTAssertTrue(try declaring("struct FlameGeometry").isEmpty,
                      "the retired geometry must not return")
        XCTAssertTrue(try declaring("struct EmberPalette").isEmpty,
                      "the retired palette must not return")
    }

    func testEveryFlamePlacementUsesTheNamedConstants() throws {
        let pattern = try NSRegularExpression(pattern: #"FlintStreakEmber\([^)]*size:\s*[0-9]"#)
        for source in try sources() {
            let range = NSRange(source.text.startIndex..., in: source.text)
            XCTAssertNil(pattern.firstMatch(in: source.text, range: range),
                         "\(source.name) passes a numeric flame size instead of StreakFlamePlacement")
        }
        XCTAssertEqual(StreakFlamePlacement.badge, 24)
        XCTAssertEqual(StreakFlamePlacement.sheet, 148)
        XCTAssertEqual(StreakFlamePlacement.insights, 160)
    }
}
