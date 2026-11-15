import SwiftUI
import FamilyControls
import FlintCore
import UserNotifications
import UIKit

/// The You tab: is protection healthy, and everything that isn't a rule.
struct SettingsView: View {
    @EnvironmentObject private var appLock: FlintAppLock
    @State private var showSetPIN = false
    @State private var pinAction: PINAction?
    @State private var openSetupAfterAuthentication = false
    @State private var armingHealth = FlintGroupStore()?.loadArmingHealth() ?? FlintArmingHealth()
    @State private var authStatus = AuthorizationCenter.shared.authorizationStatus
    @State private var passStatus: FlintEmergencyPassStatus?
    @State private var passRefreshID = UUID()
    @State private var showPass = false
    @State private var confirmClearHistory = false
    @State private var historyFeedback: String?
    @State private var observation = FlintProtectionObservation.load()
    @State private var sessionHealth = FlintSessionHealth.load()
    @State private var activeSession = FlintSessionController().activeSession
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    HStack(alignment: .center) {
                        Text("You").flintTitle(32).accessibilityAddTraits(.isHeader)
                        Spacer()
                        FlintDotPill(text: "No account", tint: FlintBrand.mist, dot: nil, icon: "lock")
                    }
                    .padding(.top, 8)

                    healthCard.padding(.top, 24)

                    quickActions.padding(.top, 10)

                    FlintLiveActivitySetting()
                        .padding(.top, 6)
                    FlintRemovalGuardSetting()

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
                        NavigationLink { AppsListView() } label: {
                            FlintRow(title: String(localized: "Apps and sites"),
                                     subtitle: String(localized: "What covers each one now"), chevron: true)
                        }
                        NavigationLink { WebRestrictionsView() } label: {
                            FlintRow(title: "Website rules", subtitle: "Safari restrictions and Private Browsing", chevron: true)
                        }
                        NavigationLink { FocusFilterView() } label: {
                            FlintRow(title: "Shortcuts and Focus modes", subtitle: "Start and stop with a system Focus", chevron: true)
                        }
                        NavigationLink { HelpView() } label: {
                            FlintRow(title: String(localized: "How Flint works"),
                                     subtitle: String(localized: "Strictness, Emergency Pass and limits"), chevron: true)
                        }
                        Link(destination: URL(string: "https://github.com/Prakashmaheshwaran/flint-app")!) {
                            FlintRow(title: "About Flint", value: "Open source", chevron: true)
                        }
                    }
                    .buttonStyle(.plain)
                    .padding(.top, 18)

                    Button { confirmClearHistory = true } label: {
                        FlintRow(title: "Clear focus history", subtitle: FocusHistoryText.definition)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("settings.clearFocusHistory")
                    if let historyFeedback { Text(historyFeedback).flintSecondary(13) }

                    VStack(alignment: .leading, spacing: 0) {
                        Text("Legal").flintEyebrow()
                            .accessibilityAddTraits(.isHeader)
                            .padding(.top, 28)
                        VStack(spacing: 0) {
                            ForEach(FlintLegalDocumentKind.allCases) { kind in
                                NavigationLink { LegalDocumentView(kind: kind) } label: {
                                    FlintRow(title: kind.title, chevron: true)
                                }
                                .accessibilityIdentifier(kind.accessibilityIdentifier)
                            }
                        }
                        .buttonStyle(.plain)
                        .padding(.top, 8)
                    }

                    FlintPrivacyNote(text: "No account. No analytics. Your lists and history stay on this phone.",
                                     color: FlintBrand.stone)
                        .padding(.top, 22)
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 32)
            }
            .background(FlintGroundBackground(glow: 0.12))
            .flintTabBarInset()
            .toolbar(.hidden, for: .navigationBar)
            .onAppear(perform: refresh)
            .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in refresh() }
            .sheet(isPresented: $showPass, onDismiss: refresh) {
                EmergencyPassSheet(onKeep: { showPass = false }, onApplied: refresh)
            }
            .confirmationDialog("Clear focus history?", isPresented: $confirmClearHistory, titleVisibility: .visible) {
                Button("Clear focus history", role: .destructive) {
                    FocusHistoryModel.shared.clear { success, keptRunningSession in
                        historyFeedback = FocusHistoryText.clearFeedback(success: success, keptRunningSession: keptRunningSession)
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("Past focus sessions will be removed from this device. A running session will be kept.")
            }
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

    /// M16's action card: notifications, and the way into the Emergency Pass.
    private var quickActions: some View {
        VStack(spacing: 10) {
            CompletionNotificationSettingsRow()
            Button { showPass = true } label: {
                actionCard(icon: "key.viewfinder",
                           title: "Emergency Pass",
                           detail: passStatus?.label ?? "Protection state unavailable",
                           trailing: passStatus?.isAvailable == true ? "Available" : "Details")
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("settings.emergencyPass")
        }
    }

    private func actionCard(icon: String, title: String, detail: String, trailing: String?) -> some View {
        HStack(spacing: 14) {
            Image(systemName: icon)
                .font(.system(size: 24, weight: .regular))
                .foregroundStyle(FlintBrand.emberText)
                .frame(width: 28)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(FlintFont.text(15, weight: .semibold))
                    .foregroundStyle(FlintBrand.bone)
                Text(detail)
                    .font(FlintFont.text(13))
                    .foregroundStyle(FlintBrand.mist)
                    .lineSpacing(2)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer(minLength: 8)
            if let trailing {
                Text(trailing)
                    .font(FlintFont.text(13, weight: .semibold))
                    .foregroundStyle(FlintBrand.emberText)
                    .fixedSize()
            }
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 14))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(FlintBrand.card))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
        .contentShape(Rectangle())
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
        let request = UUID(); passRefreshID = request
        Task {
            let result = await FlintEmergencyPassController.displayStatus()
            guard passRefreshID == request else { return }
            passStatus = try? result.get()
        }
        observation = FlintProtectionObservation.load()
        sessionHealth = FlintSessionHealth.load()
        activeSession = FlintSessionController().activeSession
    }

    private var health: FlintArmingHealth {
        var domains = observation?.domains ?? [:]
        for (name, report) in armingHealth.domains {
            if domains[name] == nil || report.updatedAt >= domains[name]!.updatedAt { domains[name] = report }
        }
        return FlintArmingHealth(domains: domains)
    }

    private var stale: Bool { observation?.isStale() ?? true }
    private var healthy: Bool {
        let currentFailures = health.domains.flatMap { domain, report in
            report.failures.filter {
                domain != "session" || $0.activityName == "session"
                    || (activeSession != nil && $0.activityName != "session.lastAttempt")
            }
        }
        return authStatus == .approved && currentFailures.isEmpty && !stale
            && !FlintSessionHealthPresentation.hasCurrentIssue(health: sessionHealth, active: activeSession,
                                                               report: health.domains["session"])
    }

    private var authorizationCopy: String {
        switch authStatus {
        case .approved: return "Screen Time access allowed"
        case .denied: return "Screen Time access denied or revoked"
        case .notDetermined: return "Screen Time access not requested"
        // SDKs add authorization modes; preserve an honest fallback on older builds too.
        default: return "Screen Time access state unknown"
        }
    }

    /// Whether every enabled schedule, Time Limit, and Open Limit passed Flint's checks and was
    /// accepted by iOS at the last re-arm. iOS provides a finite, undocumented registration pool
    /// shared by Flint's monitors; Flint surfaces a refused registration instead of letting the
    /// rule die silently.
    private var healthCard: some View {
        VStack(alignment: .leading, spacing: 14) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .center, spacing: 12) {
                    healthStatus
                    Spacer(minLength: 8)
                    rulesOnLabel
                }
                VStack(alignment: .leading, spacing: 10) {
                    healthStatus
                    rulesOnLabel
                }
            }
            Rectangle().fill(FlintBrand.bone(0.08)).frame(height: 1)
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .top, spacing: 8) {
                    healthFact("Screen Time", authorizationShort)
                    healthFact("Background", backgroundShort)
                    healthFact("Emergency Pass", passShort)
                }
                VStack(alignment: .leading, spacing: 10) {
                    healthFact("Screen Time", authorizationShort).frame(maxWidth: .infinity, alignment: .leading)
                    healthFact("Background", backgroundShort).frame(maxWidth: .infinity, alignment: .leading)
                    healthFact("Emergency Pass", passShort).frame(maxWidth: .infinity, alignment: .leading)
                }
            }
            Rectangle().fill(FlintBrand.bone(0.08)).frame(height: 1)
            VStack(alignment: .leading, spacing: 8) {
                check(authStatus == .approved, authorizationCopy)
                if stale {
                    check(false, observation == nil ? "Protection has never been checked by Flint."
                        : "The last protection check is stale. Reopen Flint to check again.")
                }
                ForEach(health.domains.keys.sorted(), id: \.self) { domain in
                    if let report = health.domains[domain] {
                        if let until = report.suspendedUntil {
                            Text("\(domainTitle(domain)): suspended for recovery until \(FlintEmergencyPassStatus.dateText(until))")
                                .font(FlintFont.text(13)).foregroundStyle(FlintBrand.stone)
                        } else if domain == "webRestrictions" {
                            Text("Website rules: \(report.armed) applied (not independently verifiable)")
                                .font(FlintFont.text(13)).foregroundStyle(FlintBrand.stone)
                        } else if domain == "removalGuard" {
                            Text("App deletion restriction: \(report.armed == 1 ? "requested from Screen Time" : "not requested"). Behavior varies by iOS version.")
                                .font(FlintFont.text(13)).foregroundStyle(FlintBrand.stone)
                        } else {
                        let summary = "\(domainTitle(domain)): \(report.enabled) enabled · \(report.attempted) attempted · \(report.armed) armed · "
                            + (report.observed.map { "\($0) observed registered" } ?? "registration not observed")
                        let currentFailure = report.failures.contains {
                            domain != "session" || $0.activityName == "session"
                                || (activeSession != nil && $0.activityName != "session.lastAttempt")
                        }
                        if !currentFailure && (report.enabled == 0 || (domain == "session" && !sessionHasCurrentIssue)) {
                            Text(summary).font(FlintFont.text(13)).foregroundStyle(FlintBrand.stone)
                        } else {
                            check(!currentFailure && report.observed != nil, summary)
                        }
                        }
                        ForEach(Array(report.failures.enumerated()), id: \.offset) { _, failure in
                            Text("\(causeTitle(failure.activityName, domain: domain)) — \(failure.reason)")
                                .font(FlintFont.text(12))
                                .foregroundStyle(FlintBrand.mist)
                        }
                    }
                }
                ForEach(Array(FlintSessionHealthPresentation.messages(health: sessionHealth,
                    report: health.domains["session"], active: activeSession).enumerated()), id: \.offset) { _, message in
                    Text(message).font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
                }
                Text("Registration does not verify that a shield or background callback took effect.")
                    .font(FlintFont.text(12)).foregroundStyle(FlintBrand.stone)
                check(passStatus?.isAvailable == true, "Emergency Pass · \(passStatus?.label ?? "Protection state unavailable")")
                if health.isNearCap {
                    Text("Flint last armed \(health.armedTotal) protection activities. iOS does not publish a fixed "
                         + "limit; refusals have been observed around \(FlintArmingHealth.observedActivityThreshold). "
                         + "Consider merging schedules or removing unused rules before adding more.")
                        .font(FlintFont.text(12))
                        .foregroundStyle(FlintBrand.ember)
                }
            }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(FlintBrand.card))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
    }

    private var healthStatus: some View {
        HStack(alignment: .center, spacing: 12) {
            ZStack {
                Circle().fill((healthy ? FlintBrand.ember : FlintBrand.danger).opacity(0.14))
                    .frame(width: 40, height: 40)
                Circle().fill(healthy ? FlintBrand.ember : FlintBrand.danger)
                    .frame(width: 10, height: 10)
                    .shadow(color: (healthy ? FlintBrand.ember : FlintBrand.danger).opacity(0.9), radius: 5)
            }
            .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 2) {
                Text(passStatus?.pass.isRecovering == true ? "Protection suspended for recovery"
                    : healthy ? "Protection is healthy" : "Protection needs attention")
                    .font(FlintFont.text(16, weight: .semibold))
                    .foregroundStyle(FlintBrand.bone)
                    .fixedSize(horizontal: false, vertical: true)
                Text(checkedLine)
                    .font(FlintFont.text(13))
                    .foregroundStyle(FlintBrand.mist)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
        .accessibilityElement(children: .combine)
    }

    private var rulesOnLabel: some View {
        Text(enabledRuleCount == 0 ? "No rules on" : "\(enabledRuleCount) \(enabledRuleCount == 1 ? "rule" : "rules") on")
            .font(FlintFont.text(12, weight: .semibold))
            .foregroundStyle(enabledRuleCount == 0 ? FlintBrand.mist : FlintBrand.emberText)
            .fixedSize()
    }

    private func healthFact(_ title: String, _ value: String) -> some View {
        VStack(alignment: .leading, spacing: 3) {
            Text(title)
                .font(FlintFont.text(12, weight: .semibold))
                .foregroundStyle(FlintBrand.bone)
            Text(value)
                .font(FlintFont.text(12))
                .foregroundStyle(FlintBrand.mist)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    private var enabledRuleCount: Int {
        let store = FlintGroupStore()
        let schedules = (store?.loadRules() ?? []).filter(\.enabled).count
        let limits = (store?.loadLimits() ?? []).filter(\.enabled).count
        let opens = FlintOpenLimitRule.loadAll().filter(\.enabled).count
        return schedules + limits + opens
    }

    private var checkedLine: String {
        guard let observation else { return "Never checked" }
        let minutes = Int(max(0, Date().timeIntervalSince(observation.observedAt)) / 60)
        if minutes < 1 { return "Checked just now" }
        if minutes == 1 { return "Checked a minute ago" }
        if minutes < 60 { return "Checked \(minutes) minutes ago" }
        let hours = minutes / 60
        return hours == 1 ? "Checked an hour ago" : "Checked \(hours) hours ago"
    }

    private var authorizationShort: String {
        switch authStatus {
        case .approved: "Allowed"
        case .denied: "Off"
        case .notDetermined: "Not asked"
        default: "Unknown"
        }
    }

    private var backgroundShort: String {
        if observation == nil { return "Not checked" }
        if stale { return "Check needed" }
        return healthy ? "Running" : "Needs attention"
    }

    private var passShort: String {
        guard let passStatus else { return "Unavailable" }
        if passStatus.pass.isRecovering { return "Recovering" }
        if passStatus.isAvailable { return "Available" }
        return passStatus.nextAvailableAt.map { "Next \($0.formatted(.dateTime.month(.abbreviated).day()))" } ?? "Unavailable"
    }

    private var sessionHasCurrentIssue: Bool {
        FlintSessionHealthPresentation.hasCurrentIssue(health: sessionHealth, active: activeSession,
                                                        report: health.domains["session"])
    }

    private func domainTitle(_ domain: String) -> String {
        switch domain {
        case "session": return "Focus session"
        case "schedules": return "Schedules"
        case "limits": return "Time Limits"
        case "openLimits": return "Open Limits"
        case "webRestrictions": return "Website rules"
        case "removalGuard": return "App deletion restriction"
        default: return "Protection"
        }
    }

    private func causeTitle(_ name: String, domain: String) -> String {
        if name == "session.lastAttempt" { return "Last session attempt" }
        if domain == "session" { return activeSession?.name ?? "Focus session" }
        if let rule = FlintGroupStore()?.loadRules().first(where: { $0.monitorName == name }) { return rule.name }
        if let rule = FlintGroupStore()?.loadLimits().first(where: { $0.activityName == name }) { return rule.name }
        if let rule = FlintOpenLimitRule.loadAll().first(where: { $0.activityName == name }) { return rule.name }
        return domainTitle(domain)
    }

    private var checkedTime: some View {
        Text(observation.map { "Checked by Flint at \($0.observedAt.formatted(date: .abbreviated, time: .shortened))" }
             ?? "Never checked by Flint")
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

@MainActor
final class CompletionNotificationSettingsModel: ObservableObject {
    @Published private(set) var status: UNAuthorizationStatus?
    @Published private(set) var isRequesting = false
    @Published private(set) var enabled = true
    private let notifier: FlintCompletionNotifier

    init(notifier: FlintCompletionNotifier = .shared) { self.notifier = notifier }

    var allowed: Bool { status.map(FlintCompletionNotifier.isAuthorized) == true }
    var statusText: String { Self.statusText(status: status, enabled: enabled) }
    var showsSettingsLink: Bool { Self.showsSettingsLink(status: status) }
    static func statusText(status: UNAuthorizationStatus?, enabled: Bool) -> String {
        guard let status else { return "Checking…" }
        switch status {
        case .notDetermined: return "Optional"
        case .denied: return "Off in Settings"
        case .authorized: return enabled ? "On" : "Off"
        case .provisional: return enabled ? "On (quiet)" : "Off (quiet permission)"
        case .ephemeral: return enabled ? "On (temporary)" : "Off (temporary permission)"
        @unknown default: return "Check Settings"
        }
    }
    static func showsSettingsLink(status: UNAuthorizationStatus?) -> Bool {
        guard let status else { return false }
        switch status {
        case .notDetermined, .authorized: return false
        case .denied, .provisional, .ephemeral: return true
        @unknown default: return true
        }
    }
    func refresh() {
        notifier.authorizationStatus { [weak self] status in
            DispatchQueue.main.async {
                guard let self else { return }
                self.status = status; self.enabled = self.notifier.isEnabled
            }
        }
    }
    func request() {
        guard !isRequesting, status == .notDetermined else { return }
        isRequesting = true
        notifier.requestPermission { [weak self] _ in
            DispatchQueue.main.async {
                guard let self else { return }
                self.isRequesting = false
                self.refresh()
            }
        }
    }
    func setEnabled(_ value: Bool) { enabled = value; notifier.setEnabled(value) }
}

struct CompletionNotificationSettingsRow: View {
    @StateObject private var notifications = CompletionNotificationSettingsModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .center, spacing: 14) {
                Image(systemName: "bell")
                    .font(.system(size: 24, weight: .regular))
                    .foregroundStyle(FlintBrand.emberText)
                    .frame(width: 28)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 3) {
                    Text("Notifications")
                        .font(FlintFont.text(15, weight: .semibold))
                        .foregroundStyle(FlintBrand.bone)
                    Text("A quiet nudge when a session ends.")
                        .font(FlintFont.text(13))
                        .foregroundStyle(FlintBrand.mist)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer(minLength: 8)
                if notifications.allowed {
                    Toggle(isOn: Binding(get: { notifications.enabled }, set: notifications.setEnabled)) {
                        Text("Focus time notifications")
                    }
                    .toggleStyle(EmberToggleStyle())
                    .labelsHidden()
                    .fixedSize()
                    .accessibilityIdentifier("settings.completionNotifications.toggle")
                } else if notifications.status == .notDetermined {
                    Button(notifications.isRequesting ? "Requesting…" : "Enable", action: notifications.request)
                        .font(FlintFont.text(13, weight: .semibold))
                        .foregroundStyle(FlintBrand.emberText)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 38)
                        .background(Capsule().fill(FlintBrand.ember.opacity(0.16)))
                        .disabled(notifications.isRequesting)
                        .accessibilityIdentifier("settings.completionNotifications.request")
                } else {
                    Text(notifications.statusText)
                        .font(FlintFont.text(12, weight: .semibold))
                        .foregroundStyle(FlintBrand.mist)
                        .fixedSize()
                }
            }
            if notifications.showsSettingsLink {
                Link("Open notification settings", destination: URL(string: UIApplication.openSettingsURLString)!)
                    .font(FlintFont.text(14, weight: .semibold))
                    .foregroundStyle(FlintBrand.bone)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("settings.completionNotifications.settings")
            }
            Text("Optional. Applies to sessions you start after turning this on.")
                .font(FlintFont.text(12))
                .foregroundStyle(FlintBrand.stone)
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 14))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(FlintBrand.card))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
        .onAppear { notifications.refresh() }
        .onChange(of: scenePhase) { if $0 == .active { notifications.refresh() } }
    }
}
