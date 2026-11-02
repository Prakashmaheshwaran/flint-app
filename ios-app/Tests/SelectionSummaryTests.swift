import XCTest
@testable import Flint

/// Host-side wording around Apple's picker: counts only, singular/plural, empty parts omitted.
final class SelectionSummaryTests: XCTestCase {
    func testEmptySelectionHasNoSummary() {
        XCTAssertEqual(SelectionCounts().total, 0)
        XCTAssertEqual(SelectionCounts().summary, "")
    }

    func testSingularAndPluralParts() {
        XCTAssertEqual(SelectionCounts(apps: 1).summary, "1 app")
        XCTAssertEqual(SelectionCounts(apps: 3, categories: 1, sites: 2).summary, "3 apps · 1 category · 2 sites")
        XCTAssertEqual(SelectionCounts(categories: 2, sites: 1).summary, "2 categories · 1 site")
    }

    func testSpokenSummaryUsesCommas() {
        XCTAssertEqual(SelectionCounts(apps: 2, sites: 1).spokenSummary, "2 apps, 1 site")
    }
}
