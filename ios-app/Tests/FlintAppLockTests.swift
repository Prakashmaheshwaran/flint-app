import XCTest
import FlintCore

final class FlintAppLockTests: XCTestCase {
    @MainActor
    private func withLock(pin: String? = nil, _ body: (FlintAppLock, FlintGroupStore) -> Void) throws {
        let suite = "FlintAppLockTests.\(UUID().uuidString)"
        defer { UserDefaults(suiteName: suite)?.removePersistentDomain(forName: suite) }
        let store = try XCTUnwrap(FlintGroupStore(suiteName: suite))
        if let pin {
            // Fixture uses the legacy record format, independent of new-PIN setup policy.
            store.setPINRecord(hash: FlintPIN.hash(pin, salt: "test-salt"), salt: "test-salt")
        }
        body(FlintAppLock(store: store), store)
    }

    @MainActor func testColdLaunchAndWrongPIN() async throws {
        try withLock(pin: "2468") { lock, _ in
            XCTAssertTrue(lock.isLocked)
            XCTAssertTrue(lock.state.isProtected)
            lock.transition(to: .active)
            XCTAssertFalse(lock.unlock(with: "0000"))
            XCTAssertTrue(lock.isLocked)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertFalse(lock.state.isProtected)
        }
    }

    @MainActor func testFirstSetupLocksOnBackgroundAndAgainAfterUnlock() async throws {
        try withLock { lock, _ in
            lock.transition(to: .active)
            XCTAssertTrue(lock.savePIN("2468"))
            XCTAssertTrue(lock.pinIsSet)
            XCTAssertFalse(lock.isLocked)
            for _ in 0..<2 {
                lock.transition(to: .inactive)
                XCTAssertTrue(lock.state.isProtected)
                lock.transition(to: .background)
                lock.transition(to: .inactive)
                lock.transition(to: .active)
                XCTAssertTrue(lock.isLocked)
                XCTAssertTrue(lock.unlock(with: "2468"))
            }
        }
    }

    @MainActor func testTransientInactiveObscuresWithoutRelockingOrExpiringChange() async throws {
        try withLock(pin: "2468") { lock, _ in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertTrue(lock.authorizePINChange(currentPIN: "2468"))
            lock.transition(to: .inactive)
            XCTAssertTrue(lock.state.isProtected)
            XCTAssertFalse(lock.isLocked)
            XCTAssertFalse(lock.savePIN("1357"), "Cannot edit while inactive")
            lock.transition(to: .active)
            XCTAssertFalse(lock.state.isProtected)
            XCTAssertTrue(lock.savePIN("1357"))
        }
    }

    @MainActor func testBackgroundWithoutPINNeedsNoGate() async throws {
        try withLock { lock, _ in
            lock.transition(to: .background)
            lock.transition(to: .active)
            XCTAssertFalse(lock.isLocked)
            XCTAssertFalse(lock.state.isProtected)
            XCTAssertFalse(lock.unlock(with: ""))
        }
    }

    @MainActor func testUnlockDoesNotAuthorizePINReplacement() async throws {
        try withLock(pin: "2468") { lock, store in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertFalse(lock.savePIN("1357"))
            XCTAssertFalse(lock.authorizePINChange(currentPIN: "0000"))
            XCTAssertFalse(lock.savePIN("1357"))
            XCTAssertTrue(FlintPIN.verify("2468", store))
            XCTAssertFalse(FlintPIN.verify("1357", store))
        }
    }

    @MainActor func testAuthenticatedReplacementIsSingleUse() async throws {
        try withLock(pin: "2468") { lock, store in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertTrue(lock.authorizePINChange(currentPIN: "2468"))
            XCTAssertTrue(lock.savePIN("1357"))
            XCTAssertFalse(FlintPIN.verify("2468", store))
            XCTAssertTrue(FlintPIN.verify("1357", store))
            XCTAssertFalse(lock.savePIN("4321"))
            lock.transition(to: .background)
            lock.transition(to: .active)
            XCTAssertFalse(lock.unlock(with: "2468"))
            XCTAssertTrue(lock.unlock(with: "1357"))
        }
    }

    @MainActor func testBackgroundExpiresChangeEvenAfterNextUnlock() async throws {
        try withLock(pin: "2468") { lock, store in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertTrue(lock.authorizePINChange(currentPIN: "2468"))
            lock.transition(to: .background)
            XCTAssertFalse(lock.savePIN("1357"))
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertFalse(lock.savePIN("1357"))
            XCTAssertTrue(FlintPIN.verify("2468", store))
        }
    }

    @MainActor func testCancellationAndFailedAuthenticationExpireChange() async throws {
        try withLock(pin: "2468") { lock, _ in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertTrue(lock.authorizePINChange(currentPIN: "2468"))
            lock.cancelPINChange()
            XCTAssertFalse(lock.savePIN("1357"))
            XCTAssertTrue(lock.authorizePINChange(currentPIN: "2468"))
            XCTAssertFalse(lock.authorizePINChange(currentPIN: "0000"))
            XCTAssertFalse(lock.savePIN("1357"))
        }
    }

    @MainActor func testRemovalRequiresCurrentPINAndImmediatelyClearsGate() async throws {
        try withLock(pin: "2468") { lock, store in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertFalse(lock.removePIN(currentPIN: "0000"))
            XCTAssertTrue(FlintPIN.verify("2468", store))
            XCTAssertTrue(lock.removePIN(currentPIN: "2468"))
            XCTAssertFalse(lock.pinIsSet)
            XCTAssertFalse(lock.isLocked)
            XCTAssertNil(store.pinRecord())
            lock.transition(to: .background)
            lock.transition(to: .active)
            XCTAssertFalse(lock.state.isProtected)
            XCTAssertFalse(FlintAppLock(store: store).isLocked)
        }
    }

    @MainActor func testLockedOrInactiveSceneCannotChangeOrRemovePIN() async throws {
        try withLock(pin: "2468") { lock, store in
            lock.transition(to: .active)
            XCTAssertFalse(lock.authorizePINChange(currentPIN: "2468"))
            XCTAssertFalse(lock.removePIN(currentPIN: "2468"))
            XCTAssertTrue(lock.unlock(with: "2468"))
            lock.transition(to: .inactive)
            XCTAssertFalse(lock.unlock(with: "2468"))
            XCTAssertFalse(lock.authorizePINChange(currentPIN: "2468"))
            XCTAssertFalse(lock.removePIN(currentPIN: "2468"))
            XCTAssertTrue(FlintPIN.verify("2468", store))
        }
    }

    @MainActor func testLegacySevenDigitPINCanUnlockChangeAndRemove() async throws {
        try withLock(pin: "1234567") { lock, _ in
            lock.transition(to: .active)
            XCTAssertFalse(lock.unlock(with: "123456"))
            XCTAssertTrue(lock.unlock(with: "1234567"))
            XCTAssertTrue(lock.authorizePINChange(currentPIN: "1234567"))
            XCTAssertTrue(lock.savePIN("2468"))
        }
        try withLock(pin: "1234567") { lock, _ in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "1234567"))
            XCTAssertFalse(lock.removePIN(currentPIN: "123456"))
            XCTAssertTrue(lock.removePIN(currentPIN: "1234567"))
        }
    }

    @MainActor func testChangedStorageRelocksAndExpiresPriorAuthentication() async throws {
        try withLock(pin: "2468") { lock, store in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertTrue(lock.authorizePINChange(currentPIN: "2468"))
            FlintPIN.set("1357", store)
            lock.refresh()
            XCTAssertTrue(lock.isLocked)
            XCTAssertFalse(lock.unlock(with: "2468"))
            XCTAssertTrue(lock.unlock(with: "1357"))
            XCTAssertFalse(lock.savePIN("4321"))
            FlintPIN.clear(store)
            lock.refresh()
            XCTAssertFalse(lock.state.isProtected)
        }
    }

    @MainActor func testLateBackgroundReportCannotStrandForegroundedApp() async throws {
        try withLock(pin: "2468") { lock, _ in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            // UIKit/SwiftUI can deliver a coalesced `.background` after the scene has
            // already reactivated. It must not disable the gate on a foregrounded app.
            lock.transition(to: .background, ifSceneIs: .active)
            XCTAssertEqual(lock.state.phase, .active)
            XCTAssertFalse(lock.isLocked)
            XCTAssertFalse(lock.state.isProtected)
        }
    }

    @MainActor func testLateBackgroundReportAfterRelockLeavesGateUsable() async throws {
        try withLock(pin: "2468") { lock, _ in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            lock.transition(to: .inactive)
            lock.transition(to: .background, ifSceneIs: .background)
            XCTAssertTrue(lock.isLocked)
            lock.transition(to: .inactive)
            lock.transition(to: .active)
            XCTAssertTrue(lock.isLocked)
            // A duplicate/stale background arrives while foreground: stay locked, but
            // reconcile to a phase whose gate can actually be unlocked again.
            lock.transition(to: .background, ifSceneIs: .active)
            XCTAssertEqual(lock.state.phase, .active)
            XCTAssertTrue(lock.isLocked)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertFalse(lock.state.isProtected)
        }
    }

    @MainActor func testGenuineBackgroundReportStillLocksAndExpiresChange() async throws {
        try withLock(pin: "2468") { lock, store in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertTrue(lock.authorizePINChange(currentPIN: "2468"))
            lock.transition(to: .background, ifSceneIs: .background)
            XCTAssertEqual(lock.state.phase, .background)
            XCTAssertTrue(lock.isLocked)
            lock.transition(to: .active)
            XCTAssertTrue(lock.isLocked)
            XCTAssertFalse(lock.savePIN("1357"), "Backgrounding must still expire the change authorization")
            XCTAssertTrue(FlintPIN.verify("2468", store))
        }
    }

    @MainActor func testStaleBackgroundDoesNotExpireForegroundChangeAuthorization() async throws {
        try withLock(pin: "2468") { lock, _ in
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertTrue(lock.authorizePINChange(currentPIN: "2468"))
            // The scene stayed foreground-active, so no lock cycle occurred; a report
            // that disagrees with the live scene must not expire authentication either.
            lock.transition(to: .background, ifSceneIs: .active)
            XCTAssertTrue(lock.savePIN("1357"))
        }
    }

    @MainActor func testDuplicateAndOutOfOrderActiveReportsStayConsistent() async throws {
        try withLock(pin: "2468") { lock, _ in
            lock.transition(to: .active)
            lock.transition(to: .active)
            XCTAssertTrue(lock.unlock(with: "2468"))
            XCTAssertFalse(lock.state.isProtected)
            lock.transition(to: .inactive)
            XCTAssertTrue(lock.state.isProtected)
            lock.transition(to: .active)
            XCTAssertFalse(lock.state.isProtected)
            // Repeated stale background reports are idempotent.
            lock.transition(to: .background, ifSceneIs: .active)
            lock.transition(to: .background, ifSceneIs: .active)
            XCTAssertEqual(lock.state.phase, .active)
            XCTAssertFalse(lock.isLocked)
            XCTAssertFalse(lock.state.isProtected)
        }
    }

    @MainActor func testUnavailableStoreCannotReportSuccessfulSetup() async {
        let lock = FlintAppLock(store: nil)
        lock.transition(to: .active)
        XCTAssertFalse(lock.savePIN("2468"))
        XCTAssertFalse(lock.pinIsSet)
    }
}
