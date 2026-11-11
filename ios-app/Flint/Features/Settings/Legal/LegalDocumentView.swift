import SwiftUI
import FlintCore

/// An offline legal document: the bundled privacy policy or open-source notices, rendered as
/// plain text. Nothing here loads a URL, so a document's links can only ever be read.
struct LegalDocumentView: View {
    let kind: FlintLegalDocumentKind
    /// Injectable so the screen's loading, failure and retry paths are testable.
    var loader: (FlintLegalDocumentKind) throws -> FlintLegalDocument = { try FlintLegalDocument.load($0) }

    @State private var state = FlintLegalDocumentState.loading
    @State private var attempt = 0

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                switch state {
                case .loading:
                    loading
                case .failed:
                    failure
                case let .loaded(document):
                    content(document)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 24)
        }
        .accessibilityIdentifier("legal.document")
        .background(FlintGroundBackground())
        .flintTabBarInset()
        .navigationBarTitleDisplayMode(.inline)
        .task(id: attempt) { state = FlintLegalDocumentState.load(kind, using: loader) }
    }

    private var loading: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            ProgressView()
                .accessibilityHidden(true)
            Text("Loading the document…")
                .flintSecondary(15)
                .fixedSize(horizontal: false, vertical: true)
        }
        .padding(.top, 28)
        .accessibilityElement(children: .combine)
    }

    private var failure: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("This bundled document could not be opened.")
                .font(FlintFont.text(15))
                .foregroundStyle(FlintBrand.mist)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 28)
            Button("Try again") {
                state = .loading
                attempt += 1
            }
            .buttonStyle(GhostButtonStyle())
            .accessibilityIdentifier("legal.retry")
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private func content(_ document: FlintLegalDocument) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(verbatim: document.title)
                .flintTitle(28)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
                .accessibilityIdentifier("legal.title")
                .padding(.top, 8)
            Text(verbatim: String(localized: "Bundled with this version of Flint. Available offline."))
                .flintSecondary(15)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
            ForEach(Array(document.body.enumerated()), id: \.offset) { _, block in
                blockView(block)
            }
        }
        .textSelection(.enabled)
    }

    @ViewBuilder private func blockView(_ block: FlintLegalBlock) -> some View {
        switch block {
        case let .heading(level, text):
            if level <= 2 {
                Text(verbatim: text)
                    .flintSection()
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 26)
                    .accessibilityAddTraits(.isHeader)
            } else {
                Text(verbatim: text)
                    .flintRowTitle()
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 16)
                    .accessibilityAddTraits(.isHeader)
            }
        case let .paragraph(text):
            Text(verbatim: text)
                .font(FlintFont.text(15))
                .foregroundStyle(FlintBrand.mist)
                .lineSpacing(3)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 12)
        case let .listItem(text):
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(verbatim: "•")
                    .font(FlintFont.text(15))
                    .foregroundStyle(FlintBrand.stone)
                    .accessibilityHidden(true)
                Text(verbatim: text)
                    .font(FlintFont.text(15))
                    .foregroundStyle(FlintBrand.mist)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 8)
            .accessibilityElement(children: .combine)
        case let .verbatim(text):
            // Licenses and legal notices keep their line breaks; the card only sets them apart.
            FlintCard {
                Text(verbatim: text)
                    .font(FlintFont.text(14))
                    .foregroundStyle(FlintBrand.mist)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.top, 12)
        }
    }
}
