import Foundation

/// A legal document bundled with the app. Both files ship at the app bundle root, so this screen
/// never needs a network request, a WebView or an opened URL.
enum FlintLegalDocumentKind: String, CaseIterable, Identifiable, Hashable {
    case privacyPolicy
    case openSourceLicenses

    var id: String { rawValue }

    var resourceName: String {
        switch self {
        case .privacyPolicy: "privacy-policy"
        case .openSourceLicenses: "open-source-licenses"
        }
    }

    var title: String {
        switch self {
        case .privacyPolicy: String(localized: "Privacy policy")
        case .openSourceLicenses: String(localized: "Open-source licenses")
        }
    }

    var accessibilityIdentifier: String {
        switch self {
        case .privacyPolicy: "settings.legal.privacy"
        case .openSourceLicenses: "settings.legal.licenses"
        }
    }
}

/// One rendered block of a bundled Markdown document.
enum FlintLegalBlock: Equatable, Hashable {
    case heading(level: Int, text: String)
    case paragraph(String)
    case listItem(String)
    case verbatim(String)
}

/// The deliberately small Markdown subset the bundled policy and notices use. Android's
/// `LegalScreen` shows the same paragraphs; anything outside this subset stays literal text
/// instead of being interpreted, so the screen can never fetch or follow a link.
enum FlintLegalMarkdown {
    /// Splits `source` into blocks: ATX headings (levels 1–3), `-` lists, ``` / ```text fences and
    /// reflowed paragraphs. A blank (whitespace-only) line ends a block.
    static func parse(_ source: String) -> [FlintLegalBlock] {
        let lines = normalized(source).components(separatedBy: "\n")
        var blocks: [FlintLegalBlock] = []
        var block: [String] = []
        var fenceLines: [String]?

        func flush() {
            guard !block.isEmpty else { return }
            blocks.append(contentsOf: classify(block))
            block = []
        }

        for line in lines {
            if var open = fenceLines {
                if line == "```" {
                    blocks.append(.verbatim(open.joined(separator: "\n")))
                    fenceLines = nil
                } else {
                    open.append(line)
                    fenceLines = open
                }
                continue
            }
            if line == "```" || line == "```text" {
                flush()
                fenceLines = []
                continue
            }
            if line.trimmingCharacters(in: .whitespaces).isEmpty {
                flush()
                continue
            }
            block.append(line)
        }
        // An unclosed fence runs to the end of the document; its content stays verbatim.
        if let open = fenceLines { blocks.append(.verbatim(open.joined(separator: "\n"))) }
        flush()
        return blocks
    }

    /// `[label](url)` reads as "label (url)", or just "url" when the label is the URL. Anything
    /// malformed — a missing bracket, an empty label, whitespace inside the target — stays as
    /// written. No part of the result is ever opened by the app.
    static func inlineText(_ text: String) -> String {
        guard text.contains("[") else { return text }
        let pattern = "\\[([^\\[\\]]+)\\]\\(([^()\\s]+)\\)"
        guard let regex = try? NSRegularExpression(pattern: pattern) else { return text }
        let source = text as NSString
        let matches = regex.matches(in: text, range: NSRange(location: 0, length: source.length))
        guard !matches.isEmpty else { return text }
        var result = ""
        var cursor = 0
        for match in matches {
            let label = source.substring(with: match.range(at: 1))
            let target = source.substring(with: match.range(at: 2))
            result += source.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            result += label == target ? target : "\(label) (\(target))"
            cursor = match.range.location + match.range.length
        }
        result += source.substring(from: cursor)
        return result
    }

    private static func normalized(_ source: String) -> String {
        source
            .replacingOccurrences(of: "\r\n", with: "\n")
            .replacingOccurrences(of: "\r", with: "\n")
    }

    private static func classify(_ lines: [String]) -> [FlintLegalBlock] {
        guard let first = lines.first else { return [] }
        if let heading = heading(in: first) {
            var blocks: [FlintLegalBlock] = [.heading(level: heading.level, text: inlineText(heading.text))]
            // A heading that shares its block with further lines continues as a paragraph.
            let rest = reflowed(lines.dropFirst())
            if !rest.isEmpty { blocks.append(.paragraph(inlineText(rest))) }
            return blocks
        }
        if first.hasPrefix("- ") { return list(from: lines) }
        let paragraph = reflowed(lines)
        return paragraph.isEmpty ? [] : [.paragraph(inlineText(paragraph))]
    }

    private static func heading(in line: String) -> (level: Int, text: String)? {
        for level in [3, 2, 1] {
            let marker = String(repeating: "#", count: level) + " "
            if line.hasPrefix(marker) {
                return (level, String(line.dropFirst(marker.count)).trimmingCharacters(in: .whitespaces))
            }
        }
        return nil
    }

    private static func reflowed<S: Sequence>(_ lines: S) -> String where S.Element == String {
        lines
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty }
            .joined(separator: " ")
    }

    private static func list(from lines: [String]) -> [FlintLegalBlock] {
        var blocks: [FlintLegalBlock] = []
        var item: [String] = []

        func flushItem() {
            let text = reflowed(item)
            if !text.isEmpty { blocks.append(.listItem(inlineText(text))) }
            item = []
        }

        for line in lines {
            if line.hasPrefix("- ") {
                flushItem()
                item = [String(line.dropFirst(2))]
            } else {
                item.append(line)
            }
        }
        flushItem()
        return blocks
    }
}

enum FlintLegalDocumentError: Error, Equatable {
    case missing
    case unreadable
}

/// A parsed, offline document plus the title its screen shows.
struct FlintLegalDocument: Equatable {
    let kind: FlintLegalDocumentKind
    let blocks: [FlintLegalBlock]

    /// Reads the bundled Markdown. A missing resource is distinguished from unreadable bytes, so
    /// the screen can never present an unopened document as if it were the whole policy.
    static func load(_ kind: FlintLegalDocumentKind, bundle: Bundle = .main) throws -> FlintLegalDocument {
        guard let url = bundle.url(forResource: kind.resourceName, withExtension: "md") else {
            throw FlintLegalDocumentError.missing
        }
        guard let data = try? Data(contentsOf: url),
              let text = String(data: data, encoding: .utf8),
              !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw FlintLegalDocumentError.unreadable
        }
        return FlintLegalDocument(kind: kind, blocks: FlintLegalMarkdown.parse(text))
    }

    /// The document's own level-1 heading, else the row title.
    var title: String {
        for block in blocks {
            if case let .heading(level, text) = block, level == 1 { return text }
        }
        return kind.title
    }

    /// Everything the screen shows beneath the title: the first level-1 heading is the title.
    var body: [FlintLegalBlock] {
        guard let index = blocks.firstIndex(where: { block in
            if case let .heading(level, _) = block { return level == 1 }
            return false
        }) else { return blocks }
        var remaining = blocks
        remaining.remove(at: index)
        return remaining
    }
}

/// Testable screen state: loading, the parsed document, or Android parity's failure text.
enum FlintLegalDocumentState: Equatable {
    case loading
    case loaded(FlintLegalDocument)
    case failed

    static func load(_ kind: FlintLegalDocumentKind,
                     using loader: (FlintLegalDocumentKind) throws -> FlintLegalDocument) -> FlintLegalDocumentState {
        do {
            return .loaded(try loader(kind))
        } catch {
            return .failed
        }
    }
}
