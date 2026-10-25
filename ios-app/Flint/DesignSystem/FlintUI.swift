import SwiftUI
import CoreText
import FlintCore

// Ember & Mist building blocks. Every value comes from design/tokens.json; the reference
// screens are design/ember-mist/*.dc.html. Screens compose these instead of styling inline.

// MARK: - Type

/// The two bundled families: Shippori Mincho for display, Hanken Grotesk for text.
enum FlintFont {
    private static let files = [
        "ShipporiMincho-Regular", "HankenGrotesk-Regular", "HankenGrotesk-Medium", "HankenGrotesk-SemiBold",
    ]
    private static var registered = false

    /// Registers the bundled TTFs with Core Text. Idempotent; call once at launch.
    static func registerAll() {
        guard !registered else { return }
        registered = true
        for name in files {
            guard let url = Bundle.main.url(forResource: name, withExtension: "ttf") else { continue }
            CTFontManagerRegisterFontsForURL(url as CFURL, .process, nil)
        }
    }

    /// Display face (titles, timers, numbers). Falls back to the system serif.
    static func display(_ size: CGFloat, relativeTo style: Font.TextStyle = .title) -> Font {
        available("ShipporiMincho-Regular")
            ? .custom("ShipporiMincho-Regular", size: size, relativeTo: style)
            : .system(size: size, design: .serif)
    }

    /// Text face (body, rows, buttons, eyebrows). Falls back to the system sans.
    static func text(_ size: CGFloat, weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        let name: String
        switch weight {
        case .semibold, .bold, .heavy, .black: name = "HankenGrotesk-SemiBold"
        case .medium: name = "HankenGrotesk-Medium"
        default: name = "HankenGrotesk-Regular"
        }
        return available(name) ? .custom(name, size: size, relativeTo: style) : .system(size: size, weight: weight)
    }

    private static func available(_ name: String) -> Bool {
        UIFont(name: name, size: 12) != nil
    }
}

extension View {
    /// 11 / 500 / +2 tracking, uppercase, mist — the quiet label above every block.
    func flintEyebrow(_ color: Color = FlintBrand.mist) -> some View {
        font(FlintFont.text(11, weight: .medium, relativeTo: .caption2))
            .tracking(2)
            .textCase(.uppercase)
            .foregroundStyle(color)
    }

    /// Display title (26–34).
    func flintTitle(_ size: CGFloat = 30) -> some View {
        font(FlintFont.display(size)).tracking(-0.3).foregroundStyle(FlintBrand.bone)
    }

    /// Display numerals for timers and counts (22–84). Digits are tabular in the bundled font.
    func flintNumber(_ size: CGFloat) -> some View {
        font(FlintFont.display(size, relativeTo: .largeTitle))
            .tracking(size >= 60 ? -1.5 : size >= 44 ? -0.8 : 0)
            .monospacedDigit()
            .foregroundStyle(FlintBrand.bone)
            .lineLimit(1)
            .minimumScaleFactor(0.6)
    }

    /// 13 / 400 / mist.
    func flintSecondary(_ size: CGFloat = 13) -> some View {
        font(FlintFont.text(size)).foregroundStyle(FlintBrand.mist)
    }

    /// Row title 15 / 500.
    func flintRowTitle() -> some View {
        font(FlintFont.text(15, weight: .medium)).foregroundStyle(FlintBrand.bone)
    }
}

// MARK: - Buttons

private struct PressScale: ViewModifier {
    let pressed: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    func body(content: Content) -> some View {
        content
            .scaleEffect(pressed && !reduceMotion ? 0.98 : 1)
            .animation(.spring(response: 0.25, dampingFraction: 0.8), value: pressed)
    }
}

/// The one ember action per screen: 56 tall, ember gradient, warm shadow.
struct EmberButtonStyle: ButtonStyle {
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(FlintFont.text(16, weight: .semibold, relativeTo: .headline))
            .tracking(0.2)
            .foregroundStyle(FlintBrand.onEmber)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(
                Capsule().fill(LinearGradient(
                    colors: [FlintBrand.emberTop, FlintBrand.ember], startPoint: .top, endPoint: .bottom))
            )
            .shadow(color: FlintBrand.ember.opacity(isEnabled ? 0.28 : 0), radius: 15, y: 10)
            .opacity(isEnabled ? 1 : 0.45)
            .modifier(PressScale(pressed: configuration.isPressed))
    }
}

/// The calm confirm: bone fill, dark text ("Done", "Keep focusing").
struct BoneButtonStyle: ButtonStyle {
    var height: CGFloat = 56
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(FlintFont.text(height < 50 ? 14 : 16, weight: .semibold, relativeTo: .headline))
            .foregroundStyle(FlintBrand.onBone)
            .frame(maxWidth: height < 50 ? nil : .infinity, minHeight: height)
            .padding(.horizontal, height < 50 ? 16 : 0)
            .background(Capsule().fill(FlintBrand.bone))
            .opacity(isEnabled ? 1 : 0.45)
            .modifier(PressScale(pressed: configuration.isPressed))
    }
}

/// The secondary text action under a primary one ("Take a break", "Start another").
struct QuietButtonStyle: ButtonStyle {
    var color: Color = FlintBrand.bone
    @Environment(\.isEnabled) private var isEnabled
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(FlintFont.text(15, weight: .medium))
            .foregroundStyle(isEnabled ? color : FlintBrand.faint)
            .frame(maxWidth: .infinity, minHeight: 48)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.6 : 1)
    }
}

/// Hairline capsule, 44 tall.
struct GhostButtonStyle: ButtonStyle {
    var tint: Color = FlintBrand.bone
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(FlintFont.text(13, weight: .medium))
            .foregroundStyle(tint)
            .padding(.horizontal, 16)
            .frame(minHeight: 44)
            .overlay(Capsule().strokeBorder(tint == FlintBrand.bone ? FlintBrand.bone(0.18) : tint.opacity(0.5)))
            .contentShape(Capsule())
            .modifier(PressScale(pressed: configuration.isPressed))
    }
}

/// 44pt round icon button with a hairline ring (−/+ steppers, "New rule").
struct CircleIconButtonStyle: ButtonStyle {
    var size: CGFloat = 48
    var filled = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(FlintBrand.bone)
            .frame(width: size, height: size)
            .background(Circle().fill(filled ? FlintBrand.bone(0.06) : .clear))
            .overlay(Circle().strokeBorder(filled ? .clear : FlintBrand.bone(0.16)))
            .contentShape(Circle())
            .modifier(PressScale(pressed: configuration.isPressed))
    }
}

/// Preset chip ("Reading 30m", duration choices). Selected = bone fill.
struct ChipButtonStyle: ButtonStyle {
    var selected = false
    var height: CGFloat = 40
    /// Fill the proposed width (equal-width choice rows).
    var stretch = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(FlintFont.text(stretch ? 14 : 13, weight: .medium))
            .monospacedDigit()
            .foregroundStyle(selected ? FlintBrand.onBone : FlintBrand.bone)
            .padding(.horizontal, 12)
            .frame(maxWidth: stretch ? .infinity : nil, minHeight: height)
            .background(Capsule().fill(selected ? FlintBrand.bone : FlintBrand.bone(0.06)))
            .overlay(Capsule().strokeBorder(selected ? FlintBrand.bone : FlintBrand.bone(0.16)))
            .contentShape(Capsule())
            .modifier(PressScale(pressed: configuration.isPressed))
    }
}

// MARK: - Switch

/// 52×32 switch: ember track + warm knob when on, bone@0.14 + mist knob when off.
struct EmberToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 14) {
            configuration.label
            Spacer(minLength: 0)
            Button { configuration.isOn.toggle() } label: {
                ZStack(alignment: configuration.isOn ? .trailing : .leading) {
                    Capsule().fill(configuration.isOn ? FlintBrand.ember : FlintBrand.bone(0.14))
                    Circle()
                        .fill(configuration.isOn ? FlintBrand.knob : FlintBrand.mist)
                        .frame(width: 26, height: 26)
                        .padding(3)
                }
                .frame(width: 52, height: 32)
                .animation(.easeOut(duration: 0.15), value: configuration.isOn)
            }
            .buttonStyle(.plain)
            .accessibilityHidden(true)
        }
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isButton)
        .accessibilityValue(configuration.isOn ? "On" : "Off")
        .accessibilityAction { configuration.isOn.toggle() }
    }
}

// MARK: - Segmented

/// Pill track with a raised segment — Block/Allow list, break level, Today/7 days.
struct FlintSegmented<Value: Hashable>: View {
    let options: [(value: Value, label: String)]
    @Binding var selection: Value
    var height: CGFloat = 40
    var compact = false

    var body: some View {
        HStack(spacing: compact ? 2 : 4) {
            ForEach(options, id: \.value) { option in
                let selected = option.value == selection
                Button { selection = option.value } label: {
                    Text(option.label)
                        .font(FlintFont.text(compact ? 13 : 14, weight: .medium))
                        .foregroundStyle(selected ? FlintBrand.bone : FlintBrand.mist)
                        .padding(.horizontal, compact ? 12 : 0)
                        .frame(maxWidth: compact ? nil : .infinity, minHeight: height)
                        .background(
                            RoundedRectangle(cornerRadius: compact ? 13 : 14, style: .continuous)
                                .fill(selected ? FlintBrand.segment : .clear)
                        )
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
        .padding(compact ? 3 : 4)
        .background(
            RoundedRectangle(cornerRadius: compact ? 16 : 18, style: .continuous)
                .fill(FlintBrand.bone(compact ? 0.06 : 0.05))
        )
        .animation(.easeOut(duration: 0.15), value: selection)
    }
}

// MARK: - Surfaces

/// Content-sized options wrap onto another row instead of compressing their text.
struct FlintWrappingLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        arrange(subviews, width: proposal.width ?? .infinity).size
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        let arrangement = arrange(subviews, width: bounds.width)
        for (index, frame) in arrangement.frames.enumerated() {
            subviews[index].place(at: CGPoint(x: bounds.minX + frame.minX, y: bounds.minY + frame.minY),
                                  proposal: ProposedViewSize(frame.size))
        }
    }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> (size: CGSize, frames: [CGRect]) {
        var frames: [CGRect] = []
        var x: CGFloat = 0, y: CGFloat = 0, rowHeight: CGFloat = 0, usedWidth: CGFloat = 0
        for subview in subviews {
            let ideal = subview.sizeThatFits(.unspecified)
            let size = subview.sizeThatFits(ProposedViewSize(width: min(ideal.width, width), height: nil))
            if x > 0 && x + size.width > width {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            frames.append(CGRect(x: x, y: y, width: size.width, height: size.height))
            usedWidth = max(usedWidth, x + size.width)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return (CGSize(width: usedWidth, height: y + rowHeight), frames)
    }
}

/// Quiet card: 20 radius, bone@0.03 fill, bone@0.10 hairline.
struct FlintCard<Content: View>: View {
    var radius: CGFloat = 20
    var padding = EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16)
    @ViewBuilder var content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) { content }
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: radius, style: .continuous).fill(FlintBrand.bone(0.03)))
            .overlay(RoundedRectangle(cornerRadius: radius, style: .continuous).strokeBorder(FlintBrand.bone(0.10)))
    }
}

/// A list row: title (+ subtitle), trailing value, optional chevron, hairline beneath.
struct FlintRow<Trailing: View>: View {
    let title: String
    var subtitle: String?
    var value: String?
    var chevron = false
    var minHeight: CGFloat = 58
    @ViewBuilder var trailing: Trailing
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        HStack(spacing: 14) {
            VStack(alignment: .leading, spacing: 2) {
                Text(title).flintRowTitle()
                if let subtitle { Text(subtitle).flintSecondary() }
                if typeSize.isAccessibilitySize, let value {
                    Text(value).flintSecondary(14).monospacedDigit()
                }
            }
            .fixedSize(horizontal: false, vertical: true)
            Spacer(minLength: 8)
            if !typeSize.isAccessibilitySize, let value { Text(value).flintSecondary(14).monospacedDigit() }
            trailing
            if chevron { FlintChevron() }
        }
        .padding(.vertical, typeSize.isAccessibilitySize ? 12 : 0)
        .frame(minHeight: minHeight)
        .overlay(alignment: .bottom) { FlintHairline() }
        .contentShape(Rectangle())
    }
}

extension FlintRow where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil, value: String? = nil, chevron: Bool = false, minHeight: CGFloat = 58) {
        self.init(title: title, subtitle: subtitle, value: value, chevron: chevron, minHeight: minHeight) { EmptyView() }
    }
}

struct FlintHairline: View {
    var opacity = 0.08
    var body: some View { Rectangle().fill(FlintBrand.bone(opacity)).frame(height: 1) }
}

struct FlintChevron: View {
    var body: some View {
        Image(systemName: "chevron.right")
            .font(.system(size: 13, weight: .regular))
            .foregroundStyle(FlintBrand.mist)
            .accessibilityHidden(true)
    }
}

/// Sheet chrome: sheet colour, grabber. Present with `.presentationDragIndicator(.hidden)`.
struct FlintSheetGrabber: View {
    var body: some View {
        Capsule().fill(FlintBrand.bone(0.2)).frame(width: 40, height: 5)
            .frame(maxWidth: .infinity)
            .padding(.top, 10)
            .accessibilityHidden(true)
    }
}

/// The lock line under privacy-sensitive screens.
struct FlintPrivacyNote: View {
    let text: String
    var color: Color = FlintBrand.mist
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Image(systemName: "lock")
                .font(.system(size: 13))
                .padding(.top, 1)
                .accessibilityHidden(true)
            Text(text).font(FlintFont.text(13)).lineSpacing(3)
        }
        .foregroundStyle(color)
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

/// Screen background for list screens: ground with the night fade at the top.
struct FlintGroundBackground: View {
    var fadeHeight: CGFloat = 260
    var body: some View {
        FlintBrand.ground
            .overlay(alignment: .top) {
                LinearGradient(
                    stops: [
                        .init(color: Color(hex: 0x0A0C11), location: 0),
                        .init(color: Color(hex: 0x16151B), location: 0.6),
                        .init(color: FlintBrand.ground, location: 1),
                    ],
                    startPoint: .top, endPoint: .bottom
                )
                .frame(height: fadeHeight)
            }
            .ignoresSafeArea()
    }
}

extension Color {
    init(hex: UInt32, opacity: Double = 1) {
        self.init(
            red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255, opacity: opacity)
    }
}

// MARK: - The ember

/// The ember: a radial-gradient orb inside a soft halo that breathes over 8 s.
struct EmberOrb: View {
    var radius: CGFloat = 58
    var haloRadius: CGFloat?
    var glows = true
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var bright = false

    var body: some View {
        let halo = haloRadius ?? radius * 2.9
        ZStack {
            Circle()
                .fill(RadialGradient(stops: [
                    .init(color: FlintBrand.ember.opacity(0.42), location: 0),
                    .init(color: FlintBrand.ember.opacity(0.12), location: 0.45),
                    .init(color: FlintBrand.ember.opacity(0), location: 1),
                ], center: .center, startRadius: 0, endRadius: halo))
                .frame(width: halo * 2, height: halo * 2)
                .opacity(glows && !reduceMotion ? (bright ? 1 : 0.75) : 0.9)
            Circle()
                .fill(RadialGradient(stops: [
                    .init(color: FlintBrand.glow, location: 0),
                    .init(color: FlintBrand.flame, location: 0.35),
                    .init(color: FlintBrand.ember, location: 0.7),
                    .init(color: Color(hex: 0xC9781A), location: 1),
                ], center: .center, startRadius: 0, endRadius: radius))
                .frame(width: radius * 2, height: radius * 2)
        }
        .onAppear {
            guard glows, !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 4).repeatForever(autoreverses: true)) { bright = true }
        }
        .accessibilityHidden(true)
    }
}

/// Full-bleed night scene: sky, stars, the ember on the horizon, three ridges and drifting
/// mist. Geometry is the 390-pt-wide reference artboard scaled to the view's width.
struct EmberLandscape: View {
    /// Vertical shift of the horizon in reference points (Today = 0, Welcome = 20, Session = 8).
    var horizonOffset: CGFloat = 0
    /// Ember centre in reference points; nil hides it (the scene behind a sheet).
    var orbCenter: CGPoint? = CGPoint(x: 195, y: 452)
    var orbRadius: CGFloat = 58
    var showsMist = true
    /// Dims the whole scene (sheets, the Emergency Pass dialog).
    var dim: Double = 0

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var drift = false

    var body: some View {
        GeometryReader { geo in
            let s = geo.size.width / 390
            let o = horizonOffset
            ZStack(alignment: .topLeading) {
                FlintBrand.ground
                // Sky stops are pinned to the reference y-positions shifted with the horizon,
                // so the dusk band always glows just behind the far ridge.
                LinearGradient(stops: Self.sky.map { hex, y in
                    Gradient.Stop(color: Color(hex: hex), location: y == 0 ? 0 : max(0, (y + o) / (844 + o)))
                }, startPoint: .top, endPoint: .bottom)
                .frame(height: (844 + o) * s)

                Canvas { ctx, _ in
                    for (x, y, r, a) in Self.stars {
                        ctx.fill(Path(ellipseIn: CGRect(x: (x - r) * s, y: (y - r) * s, width: 2 * r * s, height: 2 * r * s)),
                                 with: .color(FlintBrand.bone.opacity(a)))
                    }
                }

                if let c = orbCenter {
                    EmberOrb(radius: orbRadius * s, haloRadius: orbRadius * 2.93 * s)
                        .position(x: c.x * s, y: (c.y + o) * s)
                }

                Canvas { ctx, size in
                    ctx.scaleBy(x: s, y: s)
                    ctx.translateBy(x: 0, y: o)
                    let floor = size.height / s - o
                    ctx.fill(Self.ridge(Self.far, floor: floor), with: .color(FlintBrand.ridge))
                    ctx.fill(Self.ridge(Self.mid, floor: floor), with: .color(Color(hex: 0x1C1919)))
                    ctx.fill(Self.ridge(Self.near, floor: floor), with: .color(Color(hex: 0x111012)))
                }

                if showsMist {
                    let dx: CGFloat = reduceMotion ? 0 : (drift ? 14 : -14)
                    Ellipse().fill(FlintBrand.bone(0.07))
                        .frame(width: 470 * s, height: 46 * s).blur(radius: 14)
                        .offset(x: -40 * s + dx, y: (500 + o) * s)
                    Ellipse().fill(FlintBrand.bone(0.05))
                        .frame(width: 360 * s, height: 34 * s).blur(radius: 12)
                        .offset(x: -60 * s + dx, y: (556 + o) * s)
                }

                if dim > 0 { Color(hex: 0x07080B).opacity(dim) }
            }
            .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
            .clipped()
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
        .onAppear {
            guard showsMist, !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 11).repeatForever(autoreverses: true)) { drift = true }
        }
    }

    private static let sky: [(UInt32, CGFloat)] = [
        (0x0B0D12, 0), (0x14151B, 321), (0x2A221E, 439), (0x4B3322, 506), (0x1A1614, 608), (0x0E0D0F, 844),
    ]

    private static let stars: [(CGFloat, CGFloat, CGFloat, Double)] = [
        (40, 120, 0.9, 0.5), (300, 80, 1, 0.45), (352, 190, 0.8, 0.35), (120, 230, 0.7, 0.3), (250, 160, 0.7, 0.3),
    ]

    private typealias Curve = (CGPoint, CGPoint, CGPoint)
    private static func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x, y: y) }

    // The three ridges of design/ember-mist/E03-Today.dc.html, as (start, [cubic segments]).
    private static let far: (CGPoint, [Curve]) = (p(0, 492), [
        (p(40, 466), p(72, 464), p(106, 480)), (p(140, 496), p(162, 452), p(206, 438)),
        (p(246, 426), p(272, 468), p(306, 476)), (p(336, 483), p(362, 455), p(390, 449)),
    ])
    private static let mid: (CGPoint, [Curve]) = (p(0, 548), [
        (p(36, 530), p(62, 512), p(96, 520)), (p(132, 528), p(152, 498), p(192, 505)),
        (p(232, 512), p(252, 540), p(292, 532)), (p(330, 524), p(360, 508), p(390, 515)),
    ])
    private static let near: (CGPoint, [Curve]) = (p(0, 600), [
        (p(50, 582), p(92, 592), p(132, 588)), (p(176, 584), p(202, 566), p(246, 573)),
        (p(290, 580), p(332, 598), p(390, 590)),
    ])

    private static func ridge(_ shape: (CGPoint, [Curve]), floor: CGFloat) -> Path {
        var path = Path()
        path.move(to: shape.0)
        for (c1, c2, end) in shape.1 { path.addCurve(to: end, control1: c1, control2: c2) }
        path.addLine(to: p(390, max(floor, 844)))
        path.addLine(to: p(0, max(floor, 844)))
        path.closeSubpath()
        return path
    }
}

// MARK: - Mark

/// The Flint mark: bone stone, ember and spark facets (56-unit artboard).
struct FlintMark: View {
    var size: CGFloat = 40
    var body: some View {
        Canvas { ctx, sz in
            let k = sz.width / 56
            func poly(_ pts: [(CGFloat, CGFloat)]) -> Path {
                var p = Path()
                p.addLines(pts.map { CGPoint(x: $0.0 * k, y: $0.1 * k) })
                p.closeSubpath()
                return p
            }
            ctx.fill(poly([(14, 44), (28, 8), (36, 26), (44, 18), (30, 48)]), with: .color(FlintBrand.bone))
            ctx.fill(poly([(36, 26), (44, 18), (42, 30)]), with: .color(FlintBrand.ember))
            ctx.fill(poly([(30, 48), (42, 30), (44, 40)]), with: .color(FlintBrand.sparkHighlight))
        }
        .frame(width: size, height: size)
        .accessibilityHidden(true)
    }
}

// MARK: - Tab bar

enum FlintTab: String, CaseIterable, Hashable {
    case today, rules, insights, you

    var title: String {
        switch self {
        case .today: "Today"
        case .rules: "Rules"
        case .insights: "Insights"
        case .you: "You"
        }
    }
}

/// The floating four-tab bar. Reflow into two rows when the full labels need more width.
struct FlintTabBar: View {
    @Binding var selection: FlintTab

    var body: some View {
        ViewThatFits(in: .horizontal) {
            tabRow(FlintTab.allCases)
            VStack(spacing: 0) {
                tabRow([.today, .rules])
                tabRow([.insights, .you])
            }
        }
        .background(
            RoundedRectangle(cornerRadius: 32, style: .continuous)
                .fill(.ultraThinMaterial).environment(\.colorScheme, .dark)
                .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous)
                    .fill(Color(red: 24 / 255, green: 22 / 255, blue: 24 / 255).opacity(0.82)))
        )
        .overlay(RoundedRectangle(cornerRadius: 32, style: .continuous).strokeBorder(FlintBrand.bone(0.10)))
        .padding(.horizontal, 24)
        .padding(.bottom, 12)
    }

    private func tabRow(_ tabs: [FlintTab]) -> some View {
        HStack(spacing: 0) {
            ForEach(tabs, id: \.self) { tab in
                let selected = tab == selection
                Button { selection = tab } label: {
                    VStack(spacing: 3) {
                        FlintTabIcon(tab: tab, selected: selected)
                            .frame(width: 22, height: 22)
                        Text(tab.title)
                            .font(FlintFont.text(11, weight: selected ? .semibold : .medium, relativeTo: .caption2))
                            .fixedSize()
                    }
                    .foregroundStyle(selected ? FlintBrand.bone : FlintBrand.stone)
                    .padding(.horizontal, 8)
                    .padding(.vertical, 8)
                    .frame(maxWidth: .infinity, minHeight: 64)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(tab.title)
                .accessibilityAddTraits(selected ? .isSelected : [])
            }
        }
    }
}

/// Stroke icons from the reference (24-unit grid, 1.5 stroke). Selected icons carry one ember detail.
struct FlintTabIcon: View {
    let tab: FlintTab
    let selected: Bool

    var body: some View {
        Canvas { ctx, size in
            let k = size.width / 24
            ctx.scaleBy(x: k, y: k)
            let stroke = StrokeStyle(lineWidth: 1.5, lineCap: .round, lineJoin: .round)
            let ink = GraphicsContext.Shading.foreground
            let accent: GraphicsContext.Shading = selected ? .color(FlintBrand.ember) : .foreground
            func line(_ a: (CGFloat, CGFloat), _ b: (CGFloat, CGFloat)) -> Path {
                var p = Path(); p.move(to: CGPoint(x: a.0, y: a.1)); p.addLine(to: CGPoint(x: b.0, y: b.1)); return p
            }
            switch tab {
            case .today:
                var arc = Path()
                arc.addArc(center: CGPoint(x: 12, y: 12), radius: 8,
                           startAngle: .degrees(0), endAngle: .degrees(298.5), clockwise: false)
                ctx.stroke(arc, with: ink, style: stroke)
                ctx.fill(Path(ellipseIn: CGRect(x: 17, y: 4.8, width: 3.2, height: 3.2)), with: accent)
            case .rules:
                ctx.stroke(line((5, 7), (19, 7)), with: ink, style: stroke)
                ctx.stroke(line((5, 12), (15, 12)), with: ink, style: stroke)
                ctx.stroke(line((5, 17), (11, 17)), with: accent, style: stroke)
            case .insights:
                ctx.stroke(line((6, 19), (6, 13)), with: ink, style: stroke)
                ctx.stroke(line((12, 19), (12, 6)), with: ink, style: stroke)
                ctx.stroke(line((18, 19), (18, 10)), with: accent, style: stroke)
            case .you:
                ctx.stroke(Path(ellipseIn: CGRect(x: 8.5, y: 5.5, width: 7, height: 7)), with: ink, style: stroke)
                var body = Path()
                body.move(to: CGPoint(x: 5, y: 20))
                body.addCurve(to: CGPoint(x: 12, y: 15), control1: CGPoint(x: 6.5, y: 16.5), control2: CGPoint(x: 9, y: 15))
                body.addCurve(to: CGPoint(x: 19, y: 20), control1: CGPoint(x: 15, y: 15), control2: CGPoint(x: 17.5, y: 16.5))
                ctx.stroke(body, with: ink, style: stroke)
                if selected {
                    ctx.fill(Path(ellipseIn: CGRect(x: 10.8, y: 7.8, width: 2.4, height: 2.4)), with: accent)
                }
            }
        }
        .accessibilityHidden(true)
    }
}

/// Breathing room above the tab bar, whose actual height is reserved by RootView.
extension View {
    func flintTabBarInset() -> some View {
        safeAreaInset(edge: .bottom, spacing: 0) { Color.clear.frame(height: 8) }
    }
}

#if DEBUG
struct FlintUI_Previews: PreviewProvider {
    static var previews: some View {
        ZStack {
            EmberLandscape()
            VStack(spacing: 16) {
                Spacer()
                Text("2h 40m").flintNumber(50)
                Button("Start focus") {}.buttonStyle(EmberButtonStyle())
                Button("Done") {}.buttonStyle(BoneButtonStyle())
                FlintTabBar(selection: .constant(.today))
            }
            .padding(.horizontal, 24)
        }
        .preferredColorScheme(.dark)
        .onAppear { FlintFont.registerAll() }
    }
}
#endif
