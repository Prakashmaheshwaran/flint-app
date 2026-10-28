import XCTest
import FlintCore
@testable import Flint

final class SleepPresentationTests: XCTestCase {
    private let english = Locale(identifier: "en_US")
    private let french = Locale(identifier: "fr_FR")

    private func schedule(days: Set<Int> = [], startHour: Int = 22, startMinute: Int = 30,
                          endHour: Int = 7, endMinute: Int = 0) -> FlintSchedule {
        FlintSchedule(daysOfWeek: days, startHour: startHour, startMinute: startMinute,
                      endHour: endHour, endMinute: endMinute)
    }

    func testWeekdayLabelsUseFullLocalizedNamesWithSundayBasedStorage() {
        XCTAssertEqual(SleepPresentation.weekdayName(1, locale: english), "Sunday")
        XCTAssertEqual(SleepPresentation.weekdayName(7, locale: english), "Saturday")
        XCTAssertEqual(SleepPresentation.weekdayName(1, locale: french), "dimanche")
        XCTAssertEqual(SleepPresentation.weekdayName(2, locale: french), "lundi")
        for locale in [english, french] {
            var calendar = Calendar(identifier: .gregorian)
            calendar.locale = locale
            XCTAssertEqual((1...7).map { SleepPresentation.weekdaySymbol($0, locale: locale) },
                           calendar.veryShortStandaloneWeekdaySymbols)
        }
    }

    func testEmptyAndAllDaysBothMeanEveryNight() {
        XCTAssertEqual(SleepPresentation.daysSummary(schedule(), locale: english), "Every night")
        XCTAssertEqual(SleepPresentation.daysSummary(schedule(days: Set(1...7)), locale: english), "Every night")
        let selected = SleepPresentation.daysSummary(schedule(days: [2, 4]), locale: english)
        XCTAssertTrue(selected.contains("Monday"))
        XCTAssertTrue(selected.contains("Wednesday"))
        XCTAssertFalse(selected.contains("Tuesday"))
        let localized = SleepPresentation.daysSummary(schedule(days: [2, 4]), locale: french)
        XCTAssertTrue(localized.contains("lundi"))
        XCTAssertTrue(localized.contains("mercredi"))
    }

    func testTimeSummaryDistinguishesOvernightAndSameEvening() {
        XCTAssertTrue(SleepPresentation.timeSummary(schedule(), locale: english).hasSuffix("next morning"))
        let sameEvening = schedule(startHour: 19, startMinute: 0, endHour: 21, endMinute: 15)
        XCTAssertFalse(SleepPresentation.timeSummary(sameEvening, locale: english).contains("next morning"))
        XCTAssertEqual(SleepPresentation.clock(22, 30, locale: french), "22:30")
    }

    func testDialValueIncludesBothLocalizedTimesAndFullOvernightDuration() {
        let night = schedule()
        let value = SleepPresentation.dialValue(night, locale: english)
        XCTAssertTrue(value.contains("Bedtime \(SleepPresentation.clock(22, 30, locale: english))"))
        XCTAssertTrue(value.contains("Wake \(SleepPresentation.clock(7, 0, locale: english))"))
        XCTAssertTrue(value.contains("8 hours, 30 minutes"))
        XCTAssertTrue(SleepPresentation.dialValue(night, locale: french).contains("22:30"))
        XCTAssertTrue(SleepPresentation.duration(night, locale: french, style: .full).contains("8 heures"))
    }

    func testDurationDoesNotTurnZeroWindowIntoTwentyFourHours() {
        let zero = schedule(startHour: 7, startMinute: 0, endHour: 7)
        let value = SleepPresentation.duration(zero, locale: english, style: .full)
        XCTAssertTrue(value.contains("0"))
        XCTAssertFalse(value.contains("24"))
        XCTAssertTrue(SleepPresentation.duration(schedule(startHour: 19, startMinute: 0, endHour: 21),
                                                locale: english, style: .full).contains("2 hours"))
    }

    func testAssistSummaryNamesTheCurrentTierMapping() {
        XCTAssertEqual(SleepPresentation.assistSummary(.windDown), "Wind Down · Harder")
        XCTAssertEqual(SleepPresentation.assistSummary(.fullAssist), "Full Assist · Hardcore")
        XCTAssertEqual(SleepPresentation.assistSummary(.off), "Sleep Assist off")
    }
}
