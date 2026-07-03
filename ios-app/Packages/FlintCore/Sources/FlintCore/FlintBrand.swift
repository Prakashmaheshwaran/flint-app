import SwiftUI
#if canImport(UIKit)
import UIKit
#endif

/// Flint brand palette. Mirror of design/tokens.json — keep in sync.
/// Provides both SwiftUI `Color` (host app) and `UIColor` (shield extension) accessors.
public enum FlintBrand {
    public static let flintHex: UInt32 = 0x2C2C2A
    public static let graphiteHex: UInt32 = 0x5F5E5A
    public static let sparkHex: UInt32 = 0xEF9F27
    public static let emberHex: UInt32 = 0xFAC775
    public static let bronzeHex: UInt32 = 0xBA7517
    public static let stoneHex: UInt32 = 0xF1EFE8
    public static let onAccentHex: UInt32 = 0x412402

    public static var flint: Color { color(flintHex) }
    public static var graphite: Color { color(graphiteHex) }
    public static var spark: Color { color(sparkHex) }
    public static var ember: Color { color(emberHex) }
    public static var bronze: Color { color(bronzeHex) }
    public static var stone: Color { color(stoneHex) }
    public static var onAccent: Color { color(onAccentHex) }

    private static func color(_ hex: UInt32) -> Color {
        Color(
            red: Double((hex >> 16) & 0xFF) / 255.0,
            green: Double((hex >> 8) & 0xFF) / 255.0,
            blue: Double(hex & 0xFF) / 255.0
        )
    }

    #if canImport(UIKit)
    public static var flintUI: UIColor { uiColor(flintHex) }
    public static var sparkUI: UIColor { uiColor(sparkHex) }
    public static var stoneUI: UIColor { uiColor(stoneHex) }
    public static var onAccentUI: UIColor { uiColor(onAccentHex) }

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
