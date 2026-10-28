import SwiftUI
import FlintCore

/// First run: Welcome, then the one permission Flint needs. "Not now" lands on Today, which
/// keeps offering Screen Time access until it is granted.
struct OnboardingView: View {
    @ObservedObject var auth: AuthorizationModel
    let onFinish: () -> Void
    @State private var page = 0
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if page == 0 {
                WelcomeView { withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.3)) { page = 1 } }
                    .transition(.opacity)
            } else {
                PermissionsView(auth: auth, onFinish: onFinish)
                    .transition(.opacity)
            }
        }
        .preferredColorScheme(.dark)
    }
}

/// E01. At default sizes the composition matches the reference; at accessibility sizes the
/// column scrolls instead of shrinking or clipping its text.
private struct WelcomeView: View {
    let onBegin: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        ZStack {
            // At accessibility sizes the promises wrap down across the horizon; leave the
            // decorative orb out there so they stay legible.
            EmberLandscape(horizonOffset: 20,
                           orbCenter: typeSize.isAccessibilitySize ? nil : CGPoint(x: 195, y: 480),
                           orbRadius: 64)
            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 14) {
                        FlintMark(size: 40)
                        Text("Flint").font(FlintFont.display(48)).tracking(-0.8).foregroundStyle(FlintBrand.bone)
                            .accessibilityAddTraits(.isHeader)
                        Text("Make time for what matters.")
                            .font(FlintFont.display(18))
                            .foregroundStyle(FlintBrand.softBone)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                        Spacer(minLength: 24)
                        VStack(spacing: 18) {
                            promises
                            Button("Begin", action: onBegin)
                                .buttonStyle(EmberButtonStyle())
                            Text("Open source · built in the open")
                                .font(FlintFont.text(12, relativeTo: .caption))
                                .foregroundStyle(FlintBrand.stone)
                                .multilineTextAlignment(.center)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding(.top, typeSize.isAccessibilitySize ? 40 : 110)
                    .padding(.horizontal, 24)
                    .padding(.bottom, 12)
                    .frame(minHeight: geometry.size.height)
                }
                .scrollBounceBehaviorBasedOnSize()
            }
        }
    }

    /// One line with separators when it fits; otherwise one promise per line, never scaled down.
    private var promises: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 18) {
                Text("No account")
                Text("·").accessibilityHidden(true)
                Text("No analytics")
                Text("·").accessibilityHidden(true)
                Text("Every feature free")
            }
            .fixedSize()
            VStack(spacing: 6) {
                Text("No account")
                Text("No analytics")
                Text("Every feature free")
            }
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
        }
        .flintSecondary()
        .accessibilityElement(children: .combine)
    }
}

/// E02. The orb is decorative; at accessibility sizes the text scrolls across where it would
/// sit, so it is left out there to keep the copy legible.
private struct PermissionsView: View {
    @ObservedObject var auth: AuthorizationModel
    let onFinish: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        ZStack(alignment: .top) {
            FlintBrand.ground.ignoresSafeArea()
            EmberLandscape(horizonOffset: -224,
                           orbCenter: typeSize.isAccessibilitySize ? nil : CGPoint(x: 282, y: 486),
                           orbRadius: 30, showsMist: false)

            GeometryReader { geometry in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("Setup").flintEyebrow().frame(maxWidth: .infinity, alignment: .trailing).padding(.top, 18)
                        // Leaves the reference's sky above the title; collapses when large text
                        // needs the room.
                        Spacer().frame(height: typeSize.isAccessibilitySize ? 24 : 240)
                        title
                        Text("Flint uses Screen Time to rest your apps. You can review access in Settings.")
                            .flintSecondary(15)
                            .lineSpacing(3)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 12)

                        VStack(spacing: 0) {
                            OnboardingPermissionRow(
                                symbol: "shield", title: "Block apps and sites", detail: "Uses Screen Time",
                                status: auth.status == .approved ? "Allowed" : "Required")
                            // Optional permission rows go here, in the same style.
                        }
                        .overlay(alignment: .top) { FlintHairline(opacity: 0.10) }
                        .padding(.top, 26)

                        FlintPrivacyNote(text: "Your lists and history stay on this phone. Nothing is uploaded.")
                            .padding(.top, 14)

                        Spacer(minLength: 24)
                        VStack(spacing: 6) {
                            AuthorizationControls(auth: auth, identifierPrefix: "onboarding.authorization", onApproved: onFinish)
                                .padding(16)
                                .background(RoundedRectangle(cornerRadius: 24).fill(FlintBrand.ground.opacity(0.94)))
                            Button(action: onFinish) {
                                Text("Not now")
                                    .font(FlintFont.text(14, weight: .medium))
                                    .foregroundStyle(FlintBrand.mist)
                                    .frame(maxWidth: .infinity, minHeight: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("onboarding.skip")
                        }
                        .frame(maxWidth: .infinity)
                    }
                    .padding(.horizontal, 24)
                    .padding(.bottom, 12)
                    .frame(minHeight: geometry.size.height)
                }
                .clipped()
            }
        }
    }

    /// The reference breaks the title after "guard"; large text wraps it naturally instead.
    @ViewBuilder private var title: some View {
        Group {
            if typeSize.isAccessibilitySize {
                Text("Let Flint guard your focus.")
            } else {
                Text("Let Flint guard\nyour focus.")
            }
        }
        .flintTitle(30)
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityAddTraits(.isHeader)
    }
}

/// One permission in the setup list: icon, what it is for, the system name, and its state.
/// Rows stack under a shared top hairline.
/// VoiceOver reads it as a single element ("Block apps and sites, Uses Screen Time, Required").
struct OnboardingPermissionRow: View {
    let symbol: String
    let title: LocalizedStringKey
    let detail: LocalizedStringKey
    let status: LocalizedStringKey
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(spacing: 14))
        layout {
            ZStack {
                Circle().fill(FlintBrand.ember.opacity(0.14))
                Image(systemName: symbol).font(.system(size: 18)).foregroundStyle(FlintBrand.ember)
            }
            .frame(width: 40, height: 40)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title).flintRowTitle()
                Text(detail).flintSecondary()
            }
            .fixedSize(horizontal: false, vertical: true)
            if !typeSize.isAccessibilitySize { Spacer(minLength: 8) }
            Text(status).flintSecondary()
        }
        .padding(.vertical, typeSize.isAccessibilitySize ? 12 : 0)
        .frame(maxWidth: .infinity, minHeight: 68, alignment: .leading)
        .overlay(alignment: .bottom) { FlintHairline(opacity: 0.10) }
        .accessibilityElement(children: .combine)
    }
}

private extension View {
    /// Rubber-banding only when content overflows (iOS 16.4+); a no-op earlier.
    @ViewBuilder func scrollBounceBehaviorBasedOnSize() -> some View {
        if #available(iOS 16.4, *) {
            scrollBounceBehavior(.basedOnSize)
        } else {
            self
        }
    }
}
