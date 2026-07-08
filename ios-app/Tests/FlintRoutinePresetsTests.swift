import XCTest
import FamilyControls
import FlintCore

/// The preset library is pure data feeding the (already-tested) schedule pipeline — verify the
/// seam: valid windows, the `Calendar` weekday convention, fields carried into the drafted rule,
/// and the targets-stay-empty invariant behind the editor's Save gate.
final class FlintRoutinePresetsTests: XCTestCase {

    func testLibraryHasFourUniquelyNamedPresets() {
        XCTAssertEqual(FlintRoutinePreset.library.count, 4)
        XCTAssertEqual(
            Set(FlintRoutinePreset.library.map(\.name)).count,
            FlintRoutinePreset.library.count,
            "preset names double as ids — they must be unique"
        )
        for preset in FlintRoutinePreset.library {
            XCTAssertFalse(preset.name.isEmpty)
            XCTAssertFalse(preset.description.isEmpty)
        }
    }

    func testEveryPresetDraftsAValidScheduleRule() {
        for preset in FlintRoutinePreset.library {
            let rule = preset.draftRule()
            let s = rule.schedule
            XCTAssertTrue((0...23).contains(s.startHour), preset.name)
            XCTAssertTrue((0...59).contains(s.startMinute), preset.name)
            XCTAssertTrue((0...23).contains(s.endHour), preset.name)
            XCTAssertTrue((0...59).contains(s.endMinute), preset.name)
            XCTAssertTrue(
                s.daysOfWeek.allSatisfy { (1...7).contains($0) },
                "\(preset.name): daysOfWeek must stay in Calendar's 1...7 range"
            )
            // DeviceActivitySchedule requires at least 15 minutes between start and end.
            let start = s.startHour * 60 + s.startMinute
            let end = s.endHour * 60 + s.endMinute
            XCTAssertGreaterThanOrEqual(
                end - start, 15,
                "\(preset.name): window must be a registrable DeviceActivitySchedule"
            )
            XCTAssertTrue(rule.enabled, preset.name)
        }
    }

    func testPresetDraftsLeaveTargetsToTheUser() {
        // Presets never guess apps — FamilyControls tokens are opaque, so they couldn't — and
        // the editor's Save button stays disabled until the user picks (same gate as a blank rule).
        for preset in FlintRoutinePreset.library {
            let rule = preset.draftRule()
            XCTAssertEqual(rule.selection, FamilyActivitySelection(), preset.name)
            XCTAssertFalse(rule.allowListMode, preset.name)
        }
    }

    func testWeekdayConventionUsesCalendarNumbering() {
        // FlintSchedule.daysOfWeek is Calendar weekday numbering: 1 = Sunday … 7 = Saturday.
        XCTAssertEqual(FlintSchedule.weekdays, [2, 3, 4, 5, 6])
        XCTAssertEqual(FlintSchedule.weekend, [1, 7])

        let cal = Calendar(identifier: .gregorian)
        let monday = DateComponents(calendar: cal, year: 2024, month: 1, day: 1).date!   // Mon
        let saturday = DateComponents(calendar: cal, year: 2024, month: 1, day: 6).date! // Sat
        let sunday = DateComponents(calendar: cal, year: 2024, month: 1, day: 7).date!   // Sun

        let work = FlintRoutinePreset.library.first { $0.name == "Work hours" }!.draftRule()
        XCTAssertTrue(work.appliesOn(monday, calendar: cal))
        XCTAssertFalse(work.appliesOn(saturday, calendar: cal))
        XCTAssertFalse(work.appliesOn(sunday, calendar: cal))

        let weekend = FlintRoutinePreset.library.first { $0.name == "Weekend mornings" }!.draftRule()
        XCTAssertTrue(weekend.appliesOn(saturday, calendar: cal))
        XCTAssertTrue(weekend.appliesOn(sunday, calendar: cal))
        XCTAssertFalse(weekend.appliesOn(monday, calendar: cal))
    }

    func testPresetFieldsCarryThroughToTheDraft() {
        let work = FlintRoutinePreset.library.first { $0.name == "Work hours" }!.draftRule()
        XCTAssertEqual(work.name, "Work hours")
        XCTAssertEqual(work.breakLevel, .harder)
        XCTAssertEqual(work.schedule.daysOfWeek, FlintSchedule.weekdays)
        XCTAssertEqual(work.schedule.startHour, 9)
        XCTAssertEqual(work.schedule.startMinute, 0)
        XCTAssertEqual(work.schedule.endHour, 17)
        XCTAssertEqual(work.schedule.endMinute, 0)

        let evenings = FlintRoutinePreset.library.first { $0.name == "Evenings offline" }!.draftRule()
        XCTAssertEqual(evenings.breakLevel, .easy)
        XCTAssertTrue(evenings.schedule.daysOfWeek.isEmpty, "empty = every day")
        XCTAssertEqual(evenings.schedule.startHour, 20)
        XCTAssertEqual(evenings.schedule.endHour, 23)
        XCTAssertEqual(evenings.schedule.endMinute, 0)

        let weekend = FlintRoutinePreset.library.first { $0.name == "Weekend mornings" }!.draftRule()
        XCTAssertEqual(weekend.breakLevel, .easy)
        XCTAssertEqual(weekend.schedule.daysOfWeek, FlintSchedule.weekend)
        XCTAssertEqual(weekend.schedule.startHour, 8)
        XCTAssertEqual(weekend.schedule.endHour, 12)
    }

    func testAlwaysOnPresetAdaptsToTheWidestDailyWindow() {
        // Android models "always on" as a schedule-less rule; FlintScheduleRule requires a
        // window (it maps 1:1 onto a repeating DeviceActivitySchedule), so Social detox drafts
        // the widest daily window instead: 00:00–23:59, every day.
        let detox = FlintRoutinePreset.library.first { $0.name == "Social detox" }!
        XCTAssertNil(detox.schedule, "the preset itself keeps the always-on intent")

        let rule = detox.draftRule()
        XCTAssertEqual(rule.breakLevel, .hardcore)
        XCTAssertTrue(rule.schedule.daysOfWeek.isEmpty, "empty = every day")
        XCTAssertEqual(rule.schedule.startHour, 0)
        XCTAssertEqual(rule.schedule.startMinute, 0)
        XCTAssertEqual(rule.schedule.endHour, 23)
        XCTAssertEqual(rule.schedule.endMinute, 59)
        XCTAssertTrue(rule.appliesOn(Date()), "no weekday gate — applies whenever asked")
    }

    func testEachDraftMintsAFreshRuleID() {
        // A template is never an edit: two taps on the same card must make two distinct rules.
        let preset = FlintRoutinePreset.library[0]
        XCTAssertNotEqual(preset.draftRule().id, preset.draftRule().id)
    }

    func testDraftedRulesRoundTripThroughCodable() throws {
        for preset in FlintRoutinePreset.library {
            let rule = preset.draftRule()
            let data = try JSONEncoder().encode(rule)
            XCTAssertEqual(rule, try JSONDecoder().decode(FlintScheduleRule.self, from: data), preset.name)
        }
    }
}
