import SwiftUI
import FamilyControls
import FlintCore

/// The You tab: is protection healthy, and everything that isn't a rule.
struct SettingsView: View {
    @EnvironmentObject private var appLock: FlintAppLock
    @State private var showSetPIN = false
    @State private var pinAction: PINAction?
    @State private var openSetupAfterAuthentication = false
    @State private var armingHealth = FlintGroupStore()?.loadArmingHealth() ?? FlintArmingHealth()
    @State private var authStatus = AuthorizationCenter.shared.authorizationStatus
    @State private var passAvailable = FlintSessionController().emergencyPassAvailable()
    @State private var checkedAt = Date()
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("You").flintTitle(30).padding(.top, 18)

                    healthCard.padding(.top, 20)

                    VStack(spacing: 0) {
                        Button {
                            appLock.cancelPINChange()
                            if appLock.pinIsSet { pinAction = .change } else { showSetPIN = true }
                        } label: {
                            FlintRow(title: "App lock", value: appLock.pinIsSet ? "On" : "Off", chevron: true)
                        }
                        if appLock.pinIsSet {
                            Button { pinAction = .remove } label: {
                                FlintRow(title: "Remove PIN")
                            }
                        }
                        NavigationLink { WebRestrictionsView() } label: {
                            FlintRow(title: "Website rules", subtitle: "Safari restrictions and Private Browsing", chevron: true)
                        }
                        NavigationLink { FocusFilterView() } label: {
                            FlintRow(title: "Shortcuts and Focus modes", subtitle: "Start and stop with a system Focus", chevron: true)
                        }
                        NavigationLink { StrongerProtectionView() } label: {
                            FlintRow(title: "Stronger protection", subtitle: "Screen Time passcode", chevron: true)
                        }
                        Link(destination: URL(string: "https://github.com/Prakashmaheshwaran/flint-app")!) {
                            FlintRow(title: "About Flint", value: "Open source", chevron: true)
                        }
                    }
                    .buttonStyle(.plain)
                    .overlay(alignment: .top) { FlintHairline() }
                    .padding(.top, 22)

                    FlintPrivacyNote(text: "No account. No analytics. Your lists and history stay on this phone.",
                                     color: FlintBrand.stone)
                        .padding(.top, 22)
                }
                .padding(.horizontal, 24)
            }
            .background(FlintGroundBackground())
            .flintTabBarInset()
            .toolbar(.hidden, for: .navigationBar)
            .onAppear(perform: refresh)
            .sheet(item: $pinAction, onDismiss: finishPINAuthentication) { action in
                PINAuthenticationView(title: action == .change ? "Change PIN" : "Remove PIN",
                                      authenticate: { pin in
                    action == .change ? appLock.authorizePINChange(currentPIN: pin)
                        : appLock.removePIN(currentPIN: pin)
                }, onSuccess: {
                    openSetupAfterAuthentication = action == .change
                })
            }
            .onChange(of: appLock.state.phase) { phase in
                if phase == .background {
                    // A protected background scene must not leave a sheet dismissal
                    // animation waiting behind the separate app-lock window.
                    var transaction = Transaction()
                    transaction.disablesAnimations = true
                    withTransaction(transaction) {
                        openSetupAfterAuthentication = false
                        pinAction = nil
                        showSetPIN = false
                    }
                }
            }
            .sheet(isPresented: $showSetPIN, onDismiss: appLock.cancelPINChange) {
                PINSetupView(appLock: appLock, onSave: appLock.savePIN)
            }
        }
    }

    private enum PINAction: String, Identifiable {
        case change, remove
        var id: String { rawValue }
    }

    private func finishPINAuthentication() {
        if openSetupAfterAuthentication && !appLock.isLocked && appLock.state.phase == .active {
            showSetPIN = true
        } else {
            appLock.cancelPINChange()
        }
        openSetupAfterAuthentication = false
    }

    private func refresh() {
        appLock.refresh()
        armingHealth = FlintGroupStore()?.loadArmingHealth() ?? FlintArmingHealth()
        authStatus = AuthorizationCenter.shared.authorizationStatus
        passAvailable = FlintSessionController().emergencyPassAvailable()
        checkedAt = Date()
    }

    private var healthy: Bool { authStatus == .approved && armingHealth.isHealthy }

    /// Whether every enabled schedule, Time Limit, and Open Limit passed Flint's checks and was
    /// accepted by iOS at the last re-arm. iOS provides a finite, undocumented registration pool
    /// shared by Flint's monitors; Flint surfaces a refused registration instead of letting the
    /// rule die silently.
    private var healthCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top) {
                    healthStatus.fixedSize(horizontal: true, vertical: false)
                    Spacer(minLength: 12)
                    checkedTime.fixedSize()
                }
                VStack(alignment: .leading, spacing: 10) {
                    healthStatus
                    checkedTime
                }
            }
            VStack(alignment: .leading, spacing: 8) {
                check(authStatus == .approved, authStatus == .approved ? "Screen Time access allowed" : "Screen Time access not allowed")
                if armingHealth.enabledTotal > 0 || !armingHealth.isHealthy {
                    check(armingHealth.isHealthy, armingHealth.isHealthy
                          ? "All \(armingHealth.armedTotal) enabled \(armingHealth.armedTotal == 1 ? "rule" : "rules") armed with iOS"
                          : "\(armingHealth.armedTotal) of \(armingHealth.enabledTotal) enabled \(armingHealth.enabledTotal == 1 ? "rule" : "rules") armed")
                    ForEach(armingHealth.failures, id: \.activityName) { failure in
                        Text("\(failure.activityName) — \(failure.reason)")
                            .font(.caption2.monospaced())
                            .foregroundStyle(FlintBrand.mist)
                    }
                } else {
                    check(true, "No rules switched on yet")
                }
                check(passAvailable, passAvailable ? "Emergency Pass ready · 1 this week" : "Emergency Pass used this week")
                if armingHealth.isNearCap {
                    Text("Flint last armed \(armingHealth.armedTotal) background registrations. iOS does not publish a fixed "
                         + "limit; refusals have been observed around \(FlintArmingHealth.observedActivityThreshold). "
                         + "Consider merging schedules or removing unused rules before adding more.")
                        .font(FlintFont.text(12))
                        .foregroundStyle(FlintBrand.ember)
                }
            }
        }
        .padding(18)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(FlintBrand.bone(0.03)))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(FlintBrand.bone(0.10)))
        .accessibilityElement(children: .combine)
    }

    private var healthStatus: some View {
        let layout = typeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 10))
        return layout {
            ZStack {
                Circle().fill((healthy ? FlintBrand.ember : FlintBrand.danger).opacity(0.22)).frame(width: 22, height: 22)
                Circle().fill(healthy ? FlintBrand.ember : FlintBrand.danger).frame(width: 12, height: 12)
            }
            .accessibilityHidden(true)
            Text(healthy ? "Protection is healthy" : "Protection needs attention")
                .font(FlintFont.text(16, weight: .medium)).foregroundStyle(FlintBrand.bone)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var checkedTime: some View {
        Text("Checked \(checkedAt.formatted(date: .omitted, time: .shortened))")
            .font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func check(_ ok: Bool, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: ok ? "checkmark" : "exclamationmark")
                .font(.system(size: 11, weight: .bold))
                .foregroundStyle(ok ? FlintBrand.bone : FlintBrand.danger)
                .frame(width: 14)
            Text(text).font(FlintFont.text(13)).foregroundStyle(FlintBrand.mist)
                .fixedSize(horizontal: false, vertical: true)
        }
    }
}

/// Apple's Screen Time passcode is the real anti-uninstall lock; Flint can only point to it.
private struct StrongerProtectionView: View {
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("Stronger protection").flintTitle(28)
                Text("For true anti-uninstall protection, set a system Screen Time passcode in Settings → Screen Time. "
                     + "It prevents deleting Flint or turning blocking off. Flint can’t enforce that itself — it’s an "
                     + "Apple limitation.")
                    .flintSecondary(15)
                    .lineSpacing(4)
                Text("Hardcore sessions already stop Flint from being deleted until they end.")
                    .flintSecondary(15)
                    .lineSpacing(4)
            }
            .padding(24)
        }
        .background(FlintGroundBackground())
        .navigationBarTitleDisplayMode(.inline)
    }
}
