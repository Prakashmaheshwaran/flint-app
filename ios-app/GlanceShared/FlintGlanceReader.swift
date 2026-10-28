import Foundation

/// Replaced only by public B/F adapters, never by direct reads of enforcement preference keys.
struct FlintGlanceReader: FlintGlanceReading {
    // Public Darwin notification name supplied by B's v1 contract, not a storage key.
    static let changedNotificationName = "com.flint.peakfocus.status.changed"

    func read(at date: Date) -> FlintGlanceInput {
        .unavailable(at: date)
    }
}
