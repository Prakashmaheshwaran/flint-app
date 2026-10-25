import XCTest
import FlintCore

final class FlintPINTests: XCTestCase {
    private let invalidPINs = [
        "", "1", "12", "123", "1234567", "12345678",
        "abcd", "12a4", " 1234", "1234 ", "12 34", "    ",
        "1234\n", "12\t34", "12-34", "+1234", "١٢", "１２", "１２３４", "١٢٣٤", "1️⃣234", "12\u{200B}34"
    ]

    private func isolatedStore() throws -> FlintGroupStore {
        let suite = "flint.tests.pin.\(UUID().uuidString)"
        let defaults = try XCTUnwrap(UserDefaults(suiteName: suite))
        addTeardownBlock { defaults.removePersistentDomain(forName: suite) }
        return try XCTUnwrap(FlintGroupStore(suiteName: suite))
    }

    func testNewPINAcceptsExactlyFourFiveOrSixASCIIDigits() {
        for pin in ["1234", "12345", "123456", "0000", "01234", "000001"] {
            XCTAssertTrue(FlintPIN.isValidNewPIN(pin))
        }
    }

    func testNewPINRejectsWrongLengthAndMalformedInputWithoutNormalization() {
        for pin in invalidPINs {
            XCTAssertFalse(FlintPIN.isValidNewPIN(pin))
        }
    }

    func testNonASCIIDigitsInsideByteLengthWindowAreRejected() {
        for pin in ["١٢", "１２"] {
            // These reach the ASCII predicate: four and six UTF-8 bytes, respectively.
            XCTAssertTrue((4...6).contains(pin.utf8.count))
            XCTAssertFalse(FlintPIN.isValidNewPIN(pin))
        }
    }

    func testValidNewPINsSaveAndVerifyExactlyIncludingLeadingZeros() throws {
        let store = try isolatedStore()
        for pin in ["0123", "01234", "012345"] {
            XCTAssertTrue(FlintPIN.set(pin, store))
            XCTAssertTrue(FlintPIN.isSet(store))
            XCTAssertTrue(FlintPIN.verify(pin, store))
            XCTAssertFalse(FlintPIN.verify(String(pin.dropFirst()), store))
            XCTAssertFalse(FlintPIN.verify(pin + " ", store))
            XCTAssertFalse(FlintPIN.verify("9999", store))
        }
    }

    func testInvalidNewPINsNeverCreateARecord() throws {
        let store = try isolatedStore()
        for pin in invalidPINs {
            XCTAssertFalse(FlintPIN.set(pin, store))
            XCTAssertNil(store.pinRecord())
            XCTAssertFalse(FlintPIN.isSet(store))
        }
    }

    func testInvalidReplacementLeavesExistingPINUnchanged() throws {
        let store = try isolatedStore()
        XCTAssertTrue(FlintPIN.set("0123", store))
        let original = try XCTUnwrap(store.pinRecord())
        for pin in invalidPINs {
            XCTAssertFalse(FlintPIN.set(pin, store))
            XCTAssertEqual(store.pinRecord()?.hash, original.hash)
            XCTAssertEqual(store.pinRecord()?.salt, original.salt)
            XCTAssertTrue(FlintPIN.verify("0123", store))
        }
    }

    func testLegacyPINsRemainVerifiableUntilChangedOrRemoved() throws {
        let store = try isolatedStore()
        // Recreate records written by the old setter. Only synthetic fixtures are used.
        for legacyPIN in ["1234567", "abcd", " 1234", "１２３４"] {
            let salt = "legacy-test-salt"
            store.setPINRecord(hash: FlintPIN.hash(legacyPIN, salt: salt), salt: salt)
            XCTAssertTrue(FlintPIN.isSet(store))
            XCTAssertTrue(FlintPIN.verify(legacyPIN, store))
            XCTAssertFalse(FlintPIN.verify("1234", store))
            XCTAssertFalse(FlintPIN.set(legacyPIN, store))
            XCTAssertTrue(FlintPIN.verify(legacyPIN, store))

            XCTAssertTrue(FlintPIN.set("5678", store))
            XCTAssertFalse(FlintPIN.verify(legacyPIN, store))
            XCTAssertTrue(FlintPIN.verify("5678", store))
            FlintPIN.clear(store)
            XCTAssertFalse(FlintPIN.isSet(store))
            XCTAssertFalse(FlintPIN.verify("5678", store))
        }
    }

    func testUnavailableStoreDoesNotReportSuccessfulSave() {
        XCTAssertFalse(FlintPIN.set("1234", nil))
        XCTAssertFalse(FlintPIN.isSet(nil))
        XCTAssertFalse(FlintPIN.verify("1234", nil))
    }

    @MainActor
    func testAuthenticatedInvalidReplacementCanBeCorrectedWithoutChangingTheOldPIN() async throws {
        let store = try isolatedStore()
        let lock = FlintAppLock(store: store)
        lock.transition(to: .active)
        XCTAssertTrue(lock.savePIN("0123"))
        XCTAssertTrue(lock.authorizePINChange(currentPIN: "0123"))
        for pin in invalidPINs {
            XCTAssertFalse(lock.savePIN(pin))
            XCTAssertTrue(FlintPIN.verify("0123", store))
        }
        XCTAssertTrue(lock.savePIN("543210"))
        XCTAssertFalse(FlintPIN.verify("0123", store))
        XCTAssertTrue(FlintPIN.verify("543210", store))
    }
}
