import XCTest
@testable import Flint

/// Covers the streak copy and policy the app actually renders: the sheet label, the
/// continue action, every motivation branch, the shared mood rule and the atlas clock.
///
/// These used to be reachable only through an unshipped presentation model, so the
/// assertions here protect exactly what a person sees.
final class FocusStreakCopyTests: XCTestCase {

    func testShippedSheetCopy() {
        XCTAssertEqual(FocusHistoryText.dayStreakLabel, "Day streak")
        XCTAssertEqual(FocusHistoryText.streakContinue, "Continue")
    }

    func testMotivationForActiveShortStreak() {
        XCTAssertEqual(FocusHistoryText.streakMotivation(mood: .active, streak: 1),
                       "One completed day at a time.")
        XCTAssertEqual(FocusHistoryText.streakMotivation(mood: .active, streak: 6),
                       "One completed day at a time.")
    }

    func testMotivationSwitchesAtSevenDays() {
        XCTAssertEqual(FocusHistoryText.streakMotivation(mood: .active, streak: 7),
                       "Quiet consistency. Return tomorrow.")
        XCTAssertEqual(FocusHistoryText.streakMotivation(mood: .active, streak: 30),
                       "Quiet consistency. Return tomorrow.")
    }

    func testMotivationForWarningAndDormant() {
        XCTAssertEqual(FocusHistoryText.streakMotivation(mood: .warning, streak: 4),
                       "Your streak still holds. Finish a focus session today.")
        XCTAssertEqual(FocusHistoryText.streakMotivation(mood: .dormant, streak: 0),
                       "A completed focus session lights the first day.")
    }

    func testMoodRule() {
        XCTAssertEqual(StreakEmberMood.forStreak(0, completedToday: true), .dormant)
        XCTAssertEqual(StreakEmberMood.forStreak(0, completedToday: false), .dormant)
        XCTAssertEqual(StreakEmberMood.forStreak(-2, completedToday: true), .dormant)
        XCTAssertEqual(StreakEmberMood.forStreak(3, completedToday: true), .active)
        XCTAssertEqual(StreakEmberMood.forStreak(3, completedToday: false), .warning)
    }

    func testFrameIndexWrapsWithoutHoldingAFrame() {
        XCTAssertEqual(flameFrameIndex(elapsed: 0, frameCount: 45), 0)
        XCTAssertEqual(flameFrameIndex(elapsed: 0.059, frameCount: 45), 0)
        XCTAssertEqual(flameFrameIndex(elapsed: 0.06, frameCount: 45), 1)
        XCTAssertEqual(flameFrameIndex(elapsed: 2.64, frameCount: 45), 44)
        XCTAssertEqual(flameFrameIndex(elapsed: 2.70, frameCount: 45), 0)
        XCTAssertEqual(flameFrameIndex(elapsed: 2.76, frameCount: 45), 1)
    }

    func testFrameIndexIsSafeWithoutAnAtlas() {
        XCTAssertEqual(flameFrameIndex(elapsed: 9.99, frameCount: 0), 0)
    }

    func testAnimationGate() {
        XCTAssertTrue(flameShouldAnimate(animate: true, reduceMotion: false, mood: .active, forceAnimate: false))
        XCTAssertFalse(flameShouldAnimate(animate: false, reduceMotion: false, mood: .active, forceAnimate: false))
        XCTAssertFalse(flameShouldAnimate(animate: true, reduceMotion: true, mood: .active, forceAnimate: false))
        XCTAssertFalse(flameShouldAnimate(animate: true, reduceMotion: false, mood: .dormant, forceAnimate: false))
        XCTAssertTrue(flameShouldAnimate(animate: true, reduceMotion: false, mood: .dormant, forceAnimate: true))
    }

    func testFrameIntervalMatchesTheAtlas() {
        XCTAssertEqual(streakFrameInterval, 0.06, accuracy: 0.000_001)
    }
}
