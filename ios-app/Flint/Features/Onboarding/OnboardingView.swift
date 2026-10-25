import SwiftUI
import FlintCore

/// First run: Welcome, then the one permission Flint needs. "Not now" lands on Today, which
/// keeps offering Screen Time access until it is granted.
struct OnboardingView: View {
    @ObservedObject var auth: AuthorizationModel
    let onFinish: () -> Void
    @State private var page = 0

    var body: some View {
        ZStack {
            if page == 0 {
                WelcomeView { withAnimation(.easeInOut(duration: 0.3)) { page = 1 } }
                    .transition(.opacity)
            } else {
                PermissionsView(auth: auth, onFinish: onFinish)
                    .transition(.opacity)
            }
        }
        .preferredColorScheme(.dark)
    }
}

private struct WelcomeView: View {
    let onBegin: () -> Void

    var body: some View {
        ZStack {
            EmberLandscape(horizonOffset: 20, orbCenter: CGPoint(x: 195, y: 480), orbRadius: 64)
            VStack(spacing: 14) {
                FlintMark(size: 40)
                Text("Flint").font(FlintFont.display(48)).tracking(-0.8).foregroundStyle(FlintBrand.bone)
                Text("Make time for what matters.")
                    .font(FlintFont.display(18))
                    .foregroundStyle(FlintBrand.softBone)
                    .multilineTextAlignment(.center)
                Spacer()
                VStack(spacing: 18) {
                    HStack(spacing: 18) {
                        Text("No account")
                        Text("·").accessibilityHidden(true)
                        Text("No analytics")
                        Text("·").accessibilityHidden(true)
                        Text("Every feature free")
                    }
                    .flintSecondary()
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    Button("Begin", action: onBegin).buttonStyle(EmberButtonStyle())
                    Text("Open source · built in the open").font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone)
                }
            }
            .padding(.top, 110)
            .padding(.horizontal, 24)
            .padding(.bottom, 12)
        }
    }
}

private struct PermissionsView: View {
    @ObservedObject var auth: AuthorizationModel
    let onFinish: () -> Void

    var body: some View {
        ZStack(alignment: .top) {
            FlintBrand.ground.ignoresSafeArea()
            EmberLandscape(horizonOffset: -224, orbCenter: CGPoint(x: 282, y: 486), orbRadius: 30, showsMist: false)

            GeometryReader { geometry in
                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        Text("Setup").flintEyebrow().frame(maxWidth: .infinity, alignment: .trailing).padding(.top, 18)
                        Spacer().frame(height: 240)
                        Text("Let Flint guard\nyour focus.").flintTitle(30)
                        Text("Flint uses Screen Time to rest your apps. You can review access in Settings.")
                            .flintSecondary(15)
                            .lineSpacing(3)
                            .padding(.top, 12)

                        HStack(spacing: 14) {
                            ZStack {
                                Circle().fill(FlintBrand.ember.opacity(0.14))
                                Image(systemName: "shield").font(.system(size: 18)).foregroundStyle(FlintBrand.ember)
                            }
                            .frame(width: 40, height: 40)
                            .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 3) {
                                Text("Block apps and sites").flintRowTitle()
                                Text("Uses Screen Time").flintSecondary()
                            }
                            Spacer()
                            Text(auth.status == .approved ? "Allowed" : "Required").flintSecondary()
                        }
                        .frame(minHeight: 68)
                        .overlay(alignment: .top) { FlintHairline(opacity: 0.10) }
                        .overlay(alignment: .bottom) { FlintHairline(opacity: 0.10) }
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
}
