import SwiftUI
import FlintCore
import UserNotifications
import UIKit

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

/// M01. At default sizes the composition matches the reference; at accessibility sizes the
/// column scrolls instead of shrinking or clipping its text.
private struct WelcomeView: View {
    let onBegin: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        ZStack {
            // At accessibility sizes the promises wrap down across the horizon; leave the
            // decorative orb out there so they stay legible.
            EmberLandscape(horizonOffset: 20,
                           orbCenter: typeSize.isAccessibilitySize ? nil : CGPoint(x: 195, y: 470),
                           orbRadius: 64)
            GeometryReader { geometry in
                ScrollView {
                    VStack(spacing: 0) {
                        VStack(spacing: 14) {
                            FlintMark(size: 44)
                            Text("Flint")
                                .font(FlintFont.text(44, weight: .bold, relativeTo: .largeTitle))
                                .tracking(-1)
                                .foregroundStyle(FlintBrand.bone)
                                .accessibilityAddTraits(.isHeader)
                            Text("Make time for what matters.")
                                .flintTagline(19)
                                .frame(maxWidth: 280)
                        }
                        .padding(.top, typeSize.isAccessibilitySize ? 32 : 104)

                        Spacer(minLength: 32)
                        VStack(spacing: 14) {
                            promises
                            Button("Get started", action: onBegin)
                                .buttonStyle(EmberButtonStyle())
                            Text("Open source · built in the open")
                                .font(FlintFont.text(12, relativeTo: .caption))
                                .foregroundStyle(FlintBrand.stone)
                                .multilineTextAlignment(.center)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 40)
                    .frame(minHeight: geometry.size.height)
                }
                .scrollBounceBehaviorBasedOnSize()
            }
        }
    }

    /// Three quiet promise tiles (M01). One combined VoiceOver element.
    private var promises: some View {
        let items: [(icon: String, label: String)] = [
            ("lock", "Every feature free"),
            ("person.crop.circle.badge.xmark", "No account"),
            ("iphone", "Stays on your phone"),
        ]
        return HStack(spacing: 8) {
            ForEach(items, id: \.label) { item in
                VStack(spacing: 6) {
                    Image(systemName: item.icon)
                        .font(.system(size: 17, weight: .regular))
                        .foregroundStyle(FlintBrand.emberText)
                        .accessibilityHidden(true)
                    Text(item.label)
                        .font(FlintFont.text(12, weight: .semibold, relativeTo: .caption))
                        .foregroundStyle(FlintBrand.bone)
                        .multilineTextAlignment(.center)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .padding(.horizontal, 6)
                .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(FlintBrand.bone(0.06)))
                .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Every feature free, no account, stays on your phone")
    }
}

/// M02. The orb is decorative; at accessibility sizes the text scrolls across where it would
/// sit, so it is left out there to keep the copy legible.
private struct PermissionsView: View {
    @ObservedObject var auth: AuthorizationModel
    let onFinish: () -> Void
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var notifications = CompletionNotificationSettingsModel()
    @State private var skippedNotifications = false

    var body: some View {
        ZStack(alignment: .top) {
            FlintBrand.ground.ignoresSafeArea()
            FlintEmberBackdrop(height: 300)

            GeometryReader { geometry in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        HStack(spacing: 6) {
                            Capsule().fill(FlintBrand.ember).frame(width: 28, height: 4)
                            Capsule().fill(FlintBrand.bone(0.14)).frame(width: 28, height: 4)
                            Spacer()
                            Text("Step 1 of 2")
                                .font(FlintFont.text(12, weight: .semibold))
                                .foregroundStyle(FlintBrand.mist)
                        }
                        .padding(.top, 10)

                        ZStack {
                            RoundedRectangle(cornerRadius: 18, style: .continuous)
                                .fill(FlintBrand.ember.opacity(0.14))
                            Image(systemName: "shield")
                                .font(.system(size: 25, weight: .regular))
                                .foregroundStyle(FlintBrand.ember)
                        }
                        .frame(width: 56, height: 56)
                        .accessibilityHidden(true)
                        .padding(.top, 30)

                        title
                        Text("Two permissions, asked once. Both can be changed later in Settings.")
                            .flintSecondary(15)
                            .lineSpacing(3)
                            .fixedSize(horizontal: false, vertical: true)
                            .padding(.top, 10)

                        VStack(spacing: 10) {
                            permissionCard(
                                symbol: "lock",
                                title: "Block apps and sites",
                                detail: "Uses Screen Time to rest the apps you choose.",
                                badge: auth.status == .approved ? "Allowed" : "Required",
                                highlighted: true)
                            permissionCard(
                                symbol: "bell",
                                title: "Tell me when a session ends",
                                detail: "One quiet notification. No streak nags.",
                                badge: LocalizedStringKey(notifications.statusText),
                                highlighted: false)
                        }
                        .padding(.top, 26)

                        notificationActions
                            .padding(.top, 10)

                        VStack(alignment: .leading, spacing: 10) {
                            checkLine("Your lists and history stay on this phone.")
                            checkLine("No account, no analytics, nothing uploaded.")
                        }
                        .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16))
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(FlintBrand.bone(0.04)))
                        .padding(.top, 22)

                        Spacer(minLength: 24)
                        VStack(spacing: 6) {
                            AuthorizationControls(auth: auth, identifierPrefix: "onboarding.authorization", onApproved: onFinish)
                            Button(action: onFinish) {
                                Text("Not now")
                                    .font(FlintFont.text(14, weight: .semibold))
                                    .foregroundStyle(FlintBrand.mist)
                                    .frame(maxWidth: .infinity, minHeight: 44)
                                    .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            .accessibilityLabel("Skip setup for now")
                            .accessibilityIdentifier("onboarding.skip")
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.top, 18)
                    }
                    .padding(.horizontal, 20)
                    .padding(.bottom, 36)
                    .frame(minHeight: geometry.size.height)
                }
                .clipped()
            }
        }
        .onAppear { notifications.refresh() }
        .onChange(of: scenePhase) { if $0 == .active { notifications.refresh() } }
    }

    /// The reference breaks the title after "guard"; large text wraps it naturally instead.
    @ViewBuilder private var title: some View {
        Group {
            if typeSize.isAccessibilitySize {
                Text("Let Flint guard your focus.")
            } else {
                Text("Let Flint guard your focus.")
            }
        }
        .flintTitle(32)
        .fixedSize(horizontal: false, vertical: true)
        .padding(.top, 18)
        .accessibilityAddTraits(.isHeader)
    }

    private func permissionCard(symbol: String, title: String, detail: String,
                                badge: LocalizedStringKey, highlighted: Bool) -> some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(highlighted ? FlintBrand.ember.opacity(0.14) : FlintBrand.bone(0.07))
                Image(systemName: symbol)
                    .font(.system(size: 19, weight: .regular))
                    .foregroundStyle(highlighted ? FlintBrand.ember : FlintBrand.mist)
            }
            .frame(width: 44, height: 44)
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(FlintFont.text(15, weight: .semibold))
                    .foregroundStyle(FlintBrand.bone)
                    .fixedSize(horizontal: false, vertical: true)
                Text(detail)
                    .font(FlintFont.text(13))
                    .foregroundStyle(FlintBrand.mist)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            Text(badge)
                .font(FlintFont.text(11, weight: .semibold, relativeTo: .caption2))
                .foregroundStyle(highlighted ? FlintBrand.emberText : FlintBrand.mist)
                .padding(.horizontal, 8)
                .frame(minHeight: 24)
                .background(Capsule().fill(highlighted ? FlintBrand.ember.opacity(0.16) : FlintBrand.bone(0.07)))
                .fixedSize()
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(FlintBrand.card))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous)
            .strokeBorder(highlighted ? FlintBrand.ember.opacity(0.4) : FlintBrand.bone(0.08)))
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder private var notificationActions: some View {
        if notifications.status == .notDetermined && !skippedNotifications {
            HStack(spacing: 8) {
                Button(notifications.isRequesting ? "Requesting…" : "Allow notifications", action: notifications.request)
                    .buttonStyle(GhostButtonStyle(tint: FlintBrand.bone, height: 40))
                    .disabled(notifications.isRequesting)
                    .accessibilityIdentifier("onboarding.notifications.request")
                Button("Not now") { skippedNotifications = true }
                    .font(FlintFont.text(13, weight: .semibold))
                    .foregroundStyle(FlintBrand.mist)
                    .frame(minHeight: 40)
                    .accessibilityLabel("Skip optional notifications")
                    .accessibilityIdentifier("onboarding.notifications.skip")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        } else if notifications.showsSettingsLink {
            Link("Open notification settings", destination: URL(string: UIApplication.openSettingsURLString)!)
                .font(FlintFont.text(14, weight: .semibold))
                .foregroundStyle(FlintBrand.bone)
                .frame(minHeight: 44)
                .accessibilityIdentifier("onboarding.notifications.settings")
        }
    }

    private func checkLine(_ text: LocalizedStringKey) -> some View {
        HStack(spacing: 10) {
            Image(systemName: "checkmark")
                .font(.system(size: 12, weight: .bold))
                .foregroundStyle(FlintBrand.bone)
                .accessibilityHidden(true)
            Text(text)
                .font(FlintFont.text(13))
                .foregroundStyle(FlintBrand.mist)
                .fixedSize(horizontal: false, vertical: true)
        }
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
