import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Flint's "Ember & Mist" palette. Mirror of design/tokens.json — keep in sync.
/// Provides both SwiftUI `Color` (host app) and `UIColor` (shield extension) accessors.
public enum FlintBrand {
    public static let nightHex: UInt32 = 0x0C0E13
    public static let groundHex: UInt32 = 0x0E0D0F
    public static let sheetHex: UInt32 = 0x151417
    public static let ridgeHex: UInt32 = 0x2C2522
    public static let duskHex: UInt32 = 0x4B3322
    public static let boneHex: UInt32 = 0xEDE6DA
    public static let mistHex: UInt32 = 0xB7AE9F
    public static let stoneHex: UInt32 = 0x9C9486
    public static let emberHex: UInt32 = 0xEF9F27
    public static let glowHex: UInt32 = 0xFFE7C2
    public static let flameHex: UInt32 = 0xF7BE6A
    public static let sparkHighlightHex: UInt32 = 0xFAC775
    public static let emberTopHex: UInt32 = 0xF6B860
    public static let onEmberHex: UInt32 = 0x3A2003
    public static let onBoneHex: UInt32 = 0x141316
    public static let knobHex: UInt32 = 0xFFF6E8
    public static let faintHex: UInt32 = 0x8F887C
    public static let softBoneHex: UInt32 = 0xD8CFC0
    public static let segmentHex: UInt32 = 0x2A2826
    public static let dangerHex: UInt32 = 0xE58A7C

    public static var night: Color { color(nightHex) }
    public static var ground: Color { color(groundHex) }
    public static var sheet: Color { color(sheetHex) }
    public static var ridge: Color { color(ridgeHex) }
    public static var dusk: Color { color(duskHex) }
    public static var bone: Color { color(boneHex) }
    public static var mist: Color { color(mistHex) }
    public static var stone: Color { color(stoneHex) }
    public static var ember: Color { color(emberHex) }
    public static var glow: Color { color(glowHex) }
    public static var flame: Color { color(flameHex) }
    public static var sparkHighlight: Color { color(sparkHighlightHex) }
    public static var emberTop: Color { color(emberTopHex) }
    public static var onEmber: Color { color(onEmberHex) }
    public static var onBone: Color { color(onBoneHex) }
    public static var knob: Color { color(knobHex) }
    public static var faint: Color { color(faintHex) }
    public static var softBone: Color { color(softBoneHex) }
    public static var segment: Color { color(segmentHex) }
    public static var danger: Color { color(dangerHex) }

    /// Bone at a token alpha — every hairline, border and quiet fill (tokens.json `alpha`).
    public static func bone(_ opacity: Double) -> Color { bone.opacity(opacity) }

    // Pre-redesign names, kept so older call sites compile; each maps onto Ember & Mist.
    public static var spark: Color { ember }
    public static var bronze: Color { ember }
    public static var flint: Color { ground }
    public static var graphite: Color { stone }
    public static var onAccent: Color { onEmber }

    private static func color(_ hex: UInt32) -> Color {
        Color(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }

    #if canImport(UIKit)
    public static var nightUI: UIColor { uiColor(nightHex) }
    public static var boneUI: UIColor { uiColor(boneHex) }
    public static var mistUI: UIColor { uiColor(mistHex) }
    public static var emberUI: UIColor { uiColor(emberHex) }
    public static var onEmberUI: UIColor { uiColor(onEmberHex) }

    private static func uiColor(_ hex: UInt32) -> UIColor {
        UIColor(
            red: CGFloat((hex >> 16) & 0xFF) / 255.0,
            green: CGFloat((hex >> 8) & 0xFF) / 255.0,
            blue: CGFloat(hex & 0xFF) / 255.0,
            alpha: 1.0
        )
    }
    #endif
}
