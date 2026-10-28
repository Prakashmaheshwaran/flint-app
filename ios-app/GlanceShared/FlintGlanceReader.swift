import Foundation

/// Replaced only by public B/F adapters, never by direct reads of enforcement preference keys.
struct FlintGlanceReader: FlintGlanceReading {
    func read(at date: Date) -> FlintGlanceInput {
        .unavailable(at: date)
    }
}
