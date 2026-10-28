import XCTest
import UserNotifications
@testable import Flint

@MainActor
final class CompletionNotificationPresentationTests: XCTestCase {
    func testPermissionStatesKeepAppPreferenceSeparateFromSystemGrant() {
        XCTAssertEqual(CompletionNotificationSettingsModel.statusText(status: nil, enabled: true), "Checking…")
        XCTAssertEqual(CompletionNotificationSettingsModel.statusText(status: .notDetermined, enabled: true), "Optional")
        XCTAssertEqual(CompletionNotificationSettingsModel.statusText(status: .denied, enabled: true), "Off in Settings")
        XCTAssertEqual(CompletionNotificationSettingsModel.statusText(status: .authorized, enabled: true), "On")
        XCTAssertEqual(CompletionNotificationSettingsModel.statusText(status: .authorized, enabled: false), "Off")
        XCTAssertEqual(CompletionNotificationSettingsModel.statusText(status: .provisional, enabled: true), "On (quiet)")
        XCTAssertEqual(CompletionNotificationSettingsModel.statusText(status: .provisional, enabled: false), "Off (quiet permission)")
        XCTAssertEqual(CompletionNotificationSettingsModel.statusText(status: .ephemeral, enabled: true), "On (temporary)")
        XCTAssertEqual(CompletionNotificationSettingsModel.statusText(status: .ephemeral, enabled: false), "Off (temporary permission)")
    }
    func testDeniedQuietAndTemporaryPermissionOfferSettings() {
        for status in [UNAuthorizationStatus.denied, .provisional, .ephemeral] {
            XCTAssertTrue(CompletionNotificationSettingsModel.showsSettingsLink(status: status))
        }
        for status in [UNAuthorizationStatus.notDetermined, .authorized] {
            XCTAssertFalse(CompletionNotificationSettingsModel.showsSettingsLink(status: status))
        }
        XCTAssertFalse(CompletionNotificationSettingsModel.showsSettingsLink(status: nil))
    }
    func testUnknownSystemAuthorizationFailsToSettings() throws {
        let unknown = try XCTUnwrap(UNAuthorizationStatus(rawValue: 999))
        XCTAssertEqual(CompletionNotificationSettingsModel.statusText(status: unknown, enabled: true), "Check Settings")
        XCTAssertTrue(CompletionNotificationSettingsModel.showsSettingsLink(status: unknown))
    }
}
