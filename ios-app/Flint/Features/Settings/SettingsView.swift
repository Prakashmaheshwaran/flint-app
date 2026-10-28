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
    @State private var passAvailable = FlintSessionController().emergencyPassAvailable()
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
                    Text("You").flintTitle(30).padding(.top, 18)

                    healthCard.padding(.top, 20)

                    FlintLiveActivitySetting()

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
                                     subtitle: String(localized: "What covers each one today"), chevron: true)
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
                    .overlay(alignment: .top) { FlintHairline() }
                    .padding(.top, 22)

                    Button { confirmClearHistory = true } label: {
                        FlintRow(title: "Clear focus history", subtitle: FocusHistoryText.definition)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("settings.clearFocusHistory")
                    if let historyFeedback { Text(historyFeedback).flintSecondary(13) }
                    CompletionNotificationSettingsRow()

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
            .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in refresh() }
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
                check(authStatus == .approved, authorizationCopy)
                if stale {
                    check(false, observation == nil ? "Protection has never been checked by Flint."
                        : "The last protection check is stale. Reopen Flint to check again.")
                }
                ForEach(health.domains.keys.sorted(), id: \.self) { domain in
                    if let report = health.domains[domain] {
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
                check(passAvailable, passAvailable ? "Emergency Pass ready · 1 this week" : "Emergency Pass used this week")
                if health.isNearCap {
                    Text("Flint last armed \(health.armedTotal) protection activities. iOS does not publish a fixed "
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
            Text(healthy ? "No recorded protection issues" : "Protection needs attention")
                .font(FlintFont.text(16, weight: .medium)).foregroundStyle(FlintBrand.bone)
                .fixedSize(horizontal: false, vertical: true)
        }
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
            if notifications.allowed {
                Toggle(isOn: Binding(get: { notifications.enabled }, set: notifications.setEnabled)) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Focus time notifications").flintRowTitle()
                        Text(notifications.statusText).flintSecondary(13)
                    }
                }
                .tint(FlintBrand.ember)
                .accessibilityIdentifier("settings.completionNotifications.toggle")
            } else {
                FlintRow(title: "Focus time notifications", value: notifications.statusText)
                if notifications.status == .notDetermined {
                    Button(notifications.isRequesting ? "Requesting…" : "Allow notifications", action: notifications.request)
                        .buttonStyle(BoneButtonStyle())
                        .disabled(notifications.isRequesting)
                        .accessibilityIdentifier("settings.completionNotifications.request")
                }
            }
            if notifications.showsSettingsLink {
                Link("Open notification settings", destination: URL(string: UIApplication.openSettingsURLString)!)
                    .font(FlintFont.text(15, weight: .medium))
                    .foregroundStyle(FlintBrand.bone)
                    .frame(minHeight: 44)
                    .accessibilityIdentifier("settings.completionNotifications.settings")
            }
            Text("Optional reminders when planned focus time ends. Applies to sessions you start after turning this on.")
                .flintSecondary(13)
        }
        .padding(.vertical, 16)
        .overlay(alignment: .top) { FlintHairline() }
        .onAppear { notifications.refresh() }
        .onChange(of: scenePhase) { if $0 == .active { notifications.refresh() } }
    }
}
