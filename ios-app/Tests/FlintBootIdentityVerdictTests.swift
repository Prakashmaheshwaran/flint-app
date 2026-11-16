import XCTest
@testable import FlintCore

/// iOS 27 denies `kern.bootsessionuuid`, so `FlintClock.readBootSessionID()` falls back to the
/// wall-clock-derived `boottime:` identity. A clock change moves that identity, so it must not be
/// read as a reboot that silently resets anchors; the anomaly it produces is still reported
/// (ADR-012 §9 "never silent"), while `elapsed` keeps refusing to credit the moved boot.
final class FlintBootIdentityVerdictTests: XCTestCase {
    private let wall = Date(timeIntervalSince1970: 1_800_000_000)
    private let monotonicStart: TimeInterval = 50_000

    private func verdict(secondsElapsed: TimeInterval, wallDelta: TimeInterval,
                         anchoredBoot: String?, currentBoot: String?) -> FlintTimeIntegrity.Verdict {
        FlintTimeIntegrity.verdict(
            wallStart: wall,
            monotonicStart: monotonicStart,
            bootSessionID: anchoredBoot,
            now: wall.addingTimeInterval(wallDelta),
            monotonicNow: monotonicStart + secondsElapsed,
            currentBootSessionID: currentBoot)
    }

    /// Two real boot session ids that differ prove a reboot: nothing is claimed about skew.
    func testChangedRealBootSessionIsUnverifiable() {
        XCTAssertEqual(verdict(secondsElapsed: 600, wallDelta: 600, anchoredBoot: "boot-a", currentBoot: "boot-b"),
            .unverifiable)
        XCTAssertEqual(verdict(secondsElapsed: 20, wallDelta: 3_620, anchoredBoot: "boot-a", currentBoot: "boot-b"),
            .unverifiable, "a forward skew after a real reboot is not relabelled as a clock change")
    }

    /// A matching real boot session keeps reporting both skew directions, as before.
    func testMatchingRealBootSessionReportsSkew() {
        XCTAssertEqual(verdict(secondsElapsed: 20, wallDelta: 3_620, anchoredBoot: "boot-a", currentBoot: "boot-a"),
            .wallAhead(seconds: 3_600))
        XCTAssertEqual(verdict(secondsElapsed: 600, wallDelta: 240, anchoredBoot: "boot-a", currentBoot: "boot-a"),
            .wallBehind(seconds: 360))
    }

    /// A matching fallback identity still anchors monotonic time, so a skew is attributable.
    func testMatchingFallbackIdentityReportsSkew() {
        XCTAssertEqual(verdict(secondsElapsed: 20, wallDelta: 3_620,
            anchoredBoot: "boottime:700", currentBoot: "boottime:700"), .wallAhead(seconds: 3_600))
        XCTAssertEqual(verdict(secondsElapsed: 600, wallDelta: 240,
            anchoredBoot: "boottime:700", currentBoot: "boottime:700"), .wallBehind(seconds: 360))
    }

    /// A moved wall clock changes the fallback identity without rebooting. Wall time can only
    /// move backwards across a clock change, never across a reboot, so that direction is still
    /// reported instead of silently becoming `.unverifiable`.
    func testMovedFallbackIdentityStillReportsABackwardClockChange() {
        XCTAssertEqual(verdict(secondsElapsed: 600, wallDelta: 240,
            anchoredBoot: "boottime:1000", currentBoot: "boottime:640"), .wallBehind(seconds: 360),
            "a wall clock moved back must never be reported as merely unverifiable")
    }

    /// A forward skew under a moved fallback identity is a possible clock change. It is reported
    /// rather than turned into a silent `.unverifiable`; the moved identity alone never resets an
    /// anchor, because `elapsed` still refuses to credit that boot.
    func testMovedFallbackIdentityStillReportsAForwardClockChange() {
        XCTAssertEqual(verdict(secondsElapsed: 20, wallDelta: 3_620,
            anchoredBoot: "boottime:1000", currentBoot: "boottime:4600"), .wallAhead(seconds: 3_600),
            "a moved wall clock must be reported, not silently downgraded")
    }

    /// A real id on one side and the fallback on the other leaves the boot genuinely unknown.
    func testMixedIdentityIsUnattributable() {
        XCTAssertEqual(verdict(secondsElapsed: 20, wallDelta: 3_620, anchoredBoot: "boot-a", currentBoot: "boottime:4600"),
            .unverifiable)
        XCTAssertEqual(verdict(secondsElapsed: 600, wallDelta: 240, anchoredBoot: "boot-a", currentBoot: "boottime:240"),
            .unverifiable)
    }

    /// Sessions anchored under the fallback identity keep monotonic remaining across a reboot
    /// that the OS did not report: elapsed is refused, so the remainder is never refreshed.
    func testFallbackIdentityPreservesTheBoundedRemainingAcrossAnUnknownBoot() throws {
        let clock = FlintClock(now: { self.wall }, monotonic: { self.monotonicStart },
            bootSessionID: { "boottime:700" }, calendar: { Calendar(identifier: .gregorian) })
        let checkpoint = FlintSessionRemainingCheckpoint(remaining: 600, wall: wall,
            monotonic: monotonicStart, bootID: "boottime:700")
        XCTAssertEqual(checkpoint.elapsed(clock: clock), 0)
        let moved = FlintClock(now: { self.wall }, monotonic: { self.monotonicStart },
            bootSessionID: { "boottime:900" }, calendar: { Calendar(identifier: .gregorian) })
        XCTAssertNil(checkpoint.elapsed(clock: moved),
            "a moved identity must not credit elapsed time, so it can never add remaining time")
    }
}
