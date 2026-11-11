import XCTest
@testable import Flint

/// The bundled legal documents are read-only, offline text. These tests pin the Markdown subset,
/// the missing/unreadable split and the packaged bytes, not a rendering or network path.
final class FlintLegalDocumentTests: XCTestCase {
    // MARK: - Markdown subset

    func testHeadingsOfLevelsOneThroughThree() {
        XCTAssertEqual(FlintLegalMarkdown.parse("# One\n\n## Two\n\n### Three"), [
            .heading(level: 1, text: "One"),
            .heading(level: 2, text: "Two"),
            .heading(level: 3, text: "Three"),
        ])
    }

    func testHeadingSharingItsBlockContinuesAsAParagraph() {
        XCTAssertEqual(FlintLegalMarkdown.parse("## Title\nfirst line\n  second line  "), [
            .heading(level: 2, text: "Title"),
            .paragraph("first line second line"),
        ])
    }

    func testParagraphLinesReflowWithSingleSpaces() {
        XCTAssertEqual(FlintLegalMarkdown.parse("  one  \n\ttwo\t"), [.paragraph("one two")])
    }

    func testBlankAndWhitespaceOnlyLinesSeparateBlocks() {
        XCTAssertEqual(FlintLegalMarkdown.parse("first\n\n \t \nsecond\n\n\nthird"), [
            .paragraph("first"), .paragraph("second"), .paragraph("third"),
        ])
    }

    func testCarriageReturnsNormalizeBeforeParsing() {
        XCTAssertEqual(FlintLegalMarkdown.parse("## A\r\n\r\nbody\r\n\r\n## B\rmore"), [
            .heading(level: 2, text: "A"),
            .paragraph("body"),
            .heading(level: 2, text: "B"),
            .paragraph("more"),
        ])
    }

    func testListItemsAndContinuationLines() {
        XCTAssertEqual(FlintLegalMarkdown.parse("- one\n- two\n  continues here\nmore\n- three"), [
            .listItem("one"),
            .listItem("two continues here more"),
            .listItem("three"),
        ])
    }

    func testFencedBlockStaysVerbatimWithBlankLinesAndLinkSyntax() {
        let source = "before\n\n```text\n- not a list\n\n# not a heading\n[Flint](https://example.com)\n\nlast\n```\n\nafter"
        XCTAssertEqual(FlintLegalMarkdown.parse(source), [
            .paragraph("before"),
            .verbatim("- not a list\n\n# not a heading\n[Flint](https://example.com)\n\nlast"),
            .paragraph("after"),
        ])
    }

    func testPlainFenceIsEquivalentToTextFence() {
        XCTAssertEqual(FlintLegalMarkdown.parse("```\nraw\n```"), [.verbatim("raw")])
    }

    func testUnclosedFenceRunsToEndOfDocument() {
        XCTAssertEqual(FlintLegalMarkdown.parse("intro\n\n```\nstill raw\n\n# literal"), [
            .paragraph("intro"),
            .verbatim("still raw\n\n# literal"),
        ])
    }

    func testInlineLinksBecomeReadableText() {
        XCTAssertEqual(FlintLegalMarkdown.inlineText("See [Flint support](https://example.com/issues)."),
                       "See Flint support (https://example.com/issues).")
        XCTAssertEqual(FlintLegalMarkdown.inlineText("[https://example.com](https://example.com)"),
                       "https://example.com")
    }

    func testInlineLinksAreProcessedInHeadingsParagraphsAndListItems() {
        XCTAssertEqual(FlintLegalMarkdown.parse(
            "## [Docs](https://example.com)\n\nSee [Docs](https://example.com)\n\n- Read [Docs](https://example.com)"), [
            .heading(level: 2, text: "Docs (https://example.com)"),
            .paragraph("See Docs (https://example.com)"),
            .listItem("Read Docs (https://example.com)"),
        ])
    }

    func testMalformedLinkSyntaxStaysLiteral() {
        XCTAssertEqual(FlintLegalMarkdown.inlineText("[label](url"), "[label](url")
        XCTAssertEqual(FlintLegalMarkdown.inlineText("[label] (url)"), "[label] (url)")
        XCTAssertEqual(FlintLegalMarkdown.inlineText("[](https://example.com)"), "[](https://example.com)")
        XCTAssertEqual(FlintLegalMarkdown.inlineText("[two words](https://example.com/a b)"),
                       "[two words](https://example.com/a b)")
    }

    func testFourHashesAndHashWithoutSpaceStayParagraphText() {
        XCTAssertEqual(FlintLegalMarkdown.parse("#### deep\n\n#nospace"), [
            .paragraph("#### deep"), .paragraph("#nospace"),
        ])
    }

    // MARK: - Loading

    func testBundleWithoutTheResourceIsMissing() {
        XCTAssertThrowsError(try FlintLegalDocument.load(.privacyPolicy,
                                                        bundle: Bundle(for: FlintLegalDocumentTests.self))) {
            XCTAssertEqual($0 as? FlintLegalDocumentError, .missing)
        }
    }

    func testPresentResourceParsesAndEmptyOrNonUTF8IsUnreadable() throws {
        let source = "# Bundle title\n\nBody text.\n"
        let loaded = try FlintLegalDocument.load(.openSourceLicenses,
                                                 bundle: try temporaryBundle(contents: Data(source.utf8)))
        XCTAssertEqual(loaded.title, "Bundle title")
        XCTAssertEqual(loaded.body, [.paragraph("Body text.")])

        for contents in [Data(), Data([0xFF, 0xFE, 0xFD]), Data("\n \n".utf8)] {
            XCTAssertThrowsError(try FlintLegalDocument.load(.openSourceLicenses,
                                                            bundle: try temporaryBundle(contents: contents))) {
                XCTAssertEqual($0 as? FlintLegalDocumentError, .unreadable)
            }
        }
    }

    func testStateLoadReportsLoadedOrFailed() {
        let document = FlintLegalDocument(kind: .privacyPolicy, blocks: [.paragraph("body")])
        XCTAssertEqual(FlintLegalDocumentState.load(.privacyPolicy, using: { _ in document }), .loaded(document))
        XCTAssertEqual(FlintLegalDocumentState.load(.privacyPolicy, using: { _ in
            throw FlintLegalDocumentError.unreadable
        }), .failed)
        XCTAssertEqual(FlintLegalDocumentState.load(.privacyPolicy, using: { _ in
            throw FlintLegalDocumentError.missing
        }), .failed)
    }

    func testStateLoadPassesTheRequestedKind() {
        let document = FlintLegalDocument(kind: .openSourceLicenses, blocks: [])
        var requested: [FlintLegalDocumentKind] = []
        let state = FlintLegalDocumentState.load(.openSourceLicenses, using: { kind in
            requested.append(kind)
            return document
        })
        XCTAssertEqual(state, .loaded(document))
        XCTAssertEqual(requested, [.openSourceLicenses])
    }

    func testTitleFallsBackToTheRowTitleAndOnlyTheFirstLevelOneHeadingIsRemoved() {
        let withoutHeading = FlintLegalDocument(kind: .openSourceLicenses, blocks: [.paragraph("body")])
        XCTAssertEqual(withoutHeading.title, "Open-source licenses")
        XCTAssertEqual(withoutHeading.body, [.paragraph("body")])

        let blocks: [FlintLegalBlock] = [.heading(level: 2, text: "Intro"), .heading(level: 1, text: "First"),
                                         .paragraph("body"), .heading(level: 1, text: "Second")]
        let document = FlintLegalDocument(kind: .privacyPolicy, blocks: blocks)
        XCTAssertEqual(document.title, "First")
        XCTAssertEqual(document.body, [.heading(level: 2, text: "Intro"), .paragraph("body"),
                                       .heading(level: 1, text: "Second")])
    }

    // MARK: - Bundled documents

    func testBundledPrivacyPolicyRendersOffline() throws {
        let document = try FlintLegalDocument.load(.privacyPolicy, bundle: .main)
        XCTAssertEqual(document.title, "Flint privacy policy")
        XCTAssertTrue(document.body.contains(.heading(level: 2, text: "Your data stays on your device")))
        XCTAssertTrue(document.body.contains(.heading(level: 2, text: "Retention and deletion")))
        XCTAssertTrue(inlineTexts(document).contains {
            $0.contains("Flint support (https://github.com/Prakashmaheshwaran/flint-app/issues)")
        }, "The support link must render as readable text")
        for text in inlineTexts(document) {
            XCTAssertFalse(text.contains("]("), "Unrendered link syntax: \(text)")
            XCTAssertFalse(text.hasPrefix("#"), "Unrendered heading marker: \(text)")
        }
    }

    func testBundledLicensesCoverEveryBundledFont() throws {
        let document = try FlintLegalDocument.load(.openSourceLicenses, bundle: .main)
        XCTAssertEqual(document.title, "Open-source licenses")
        for heading in ["Hanken Grotesk", "Shippori Mincho", "Flint"] {
            XCTAssertTrue(document.body.contains(.heading(level: 2, text: heading)), heading)
        }
        let verbatim = document.body.compactMap { block -> String? in
            if case let .verbatim(text) = block { return text }
            return nil
        }.joined(separator: "\n")
        XCTAssertTrue(verbatim.contains("SIL OPEN FONT LICENSE Version 1.1"))
        XCTAssertTrue(verbatim.contains("MIT License"))

        let notices = allTexts(document)
        let fonts = try bundledFontNames()
        XCTAssertFalse(fonts.isEmpty, "The app bundle must still ship its licensed fonts")
        for name in fonts {
            XCTAssertTrue(notices.contains { $0.contains(name) }, "\(name) is not named in the bundled notices")
        }
    }

    func testPackagedPrivacyPolicyMatchesRepositorySource() throws {
        let repositoryRoot = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .deletingLastPathComponent()
        guard let expected = try? Data(contentsOf: repositoryRoot.appendingPathComponent("docs/privacy-policy.md")) else {
            throw XCTSkip("docs/privacy-policy.md is not readable from this runner")
        }
        let bundled = try XCTUnwrap(Bundle.main.url(forResource: "privacy-policy", withExtension: "md"))
        XCTAssertEqual(try Data(contentsOf: bundled), expected)
    }

    // MARK: - Helpers

    /// Every rendered block, verbatim fences included.
    private func allTexts(_ document: FlintLegalDocument) -> [String] {
        document.body.compactMap { block -> String? in
            switch block {
            case let .heading(_, text), let .paragraph(text), let .listItem(text), let .verbatim(text):
                return text
            }
        }
    }

    /// Rendered heading, paragraph and list text — the blocks inline processing touches.
    private func inlineTexts(_ document: FlintLegalDocument) -> [String] {
        document.body.compactMap { block -> String? in
            switch block {
            case let .heading(_, text), let .paragraph(text), let .listItem(text): return text
            case .verbatim: return nil
            }
        }
    }

    private func bundledFontNames() throws -> [String] {
        let urls = Bundle.main.urls(forResourcesWithExtension: "ttf", subdirectory: nil) ?? []
        if !urls.isEmpty { return urls.map(\.lastPathComponent).sorted() }
        let root = try XCTUnwrap(Bundle.main.resourceURL)
        guard let enumerator = FileManager.default.enumerator(at: root, includingPropertiesForKeys: nil) else {
            return []
        }
        var names: [String] = []
        while let entry = enumerator.nextObject() {
            guard let url = entry as? URL, url.pathExtension.lowercased() == "ttf" else { continue }
            names.append(url.lastPathComponent)
        }
        return Array(Set(names)).sorted()
    }

    /// A minimal loadable bundle, so the missing/unreadable split is tested without the app.
    private func temporaryBundle(contents: Data) throws -> Bundle {
        let root = FileManager.default.temporaryDirectory
            .appendingPathComponent(UUID().uuidString, isDirectory: true)
            .appendingPathExtension("bundle")
        try FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: root) }
        let info: [String: Any] = [
            "CFBundleIdentifier": "app.flint.focus.tests.legal",
            "CFBundlePackageType": "BNDL",
            "CFBundleVersion": "1",
            "CFBundleShortVersionString": "1.0",
        ]
        try PropertyListSerialization.data(fromPropertyList: info, format: .xml, options: 0)
            .write(to: root.appendingPathComponent("Info.plist"))
        try contents.write(to: root.appendingPathComponent("open-source-licenses.md"))
        return try XCTUnwrap(Bundle(path: root.path))
    }
}
