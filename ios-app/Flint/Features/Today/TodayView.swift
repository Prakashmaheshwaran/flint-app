import SwiftUI
import FamilyControls
import FlintCore

/// Today: the hero scene over the ridges, what is resting now, and the one way into a session.
/// The running session and its Complete screen are presented by `RootView`, over every tab.
struct TodayView: View {
    @ObservedObject var auth: AuthorizationModel
    @ObservedObject var vm: SessionViewModel
    @Binding var showStart: Bool
    var onOpenRules: () -> Void = {}
    @State private var now = Date()
    @State private var rules: [FlintScheduleRule] = []
    @ObservedObject private var history = FocusHistoryModel.shared
    @State private var lastHistoryRefresh = Date.distantPast
    @Environment(\.scenePhase) private var scenePhase
    @State private var health = FlintArmingHealth()
    @State private var observation: FlintProtectionObservation?
    @Environment(\.dynamicTypeSize) private var typeSize
    @Environment(\.flintTabBarHeight) private var barHeight

    private var compact: Bool { typeSize.isAccessibilitySize }

    var body: some View {
        GeometryReader { geometry in
            ZStack(alignment: .top) {
                if compact {
                    FlintGroundBackground()
                } else {
                    FlintBrand.ground.ignoresSafeArea()
                }

                ScrollView {
                    VStack(alignment: .leading, spacing: 0) {
                        if compact {
                            compactHero
                        } else {
                            hero
                        }
                        nowSection
                            .padding(.horizontal, 20)
                        upNext
                            .padding(.horizontal, 20)
                            .padding(.top, 20)
                        footer
                            .padding(.horizontal, 20)
                            .padding(.top, footerIsEmpty ? 0 : 20)
                    }
                    .padding(.bottom, FlintTabBarSpacer.height(measured: barHeight, accessibilitySize: compact) + 16)
                    .frame(minHeight: geometry.size.height, alignment: .top)
                }
                .clipped()
            }
        }
        .sheet(isPresented: $showStart) {
            StartFocusSheet(vm: vm) { showStart = false }
        }
        .task {
            auth.refresh()
            rules = FlintSchedulesController().rules()
            refreshHistory()
            refreshHealth()
        }
        .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in
            rules = FlintSchedulesController().rules()
            refreshHealth()
        }
        .onChange(of: scenePhase) { if $0 == .active { refreshHistory() } }
        .onReceive(Timer.publish(every: 30, on: .main, in: .common).autoconnect()) { date in
            now = date
            if history.summary?.sessions.contains(where: \.isInProgress) == true || date.timeIntervalSince(lastHistoryRefresh) >= 300 {
                refreshHistory()
            }
        }
    }

    // MARK: Hero

    private var hero: some View {
        ZStack(alignment: .top) {
            TodayHeroScene(height: 380)
            VStack(spacing: 0) {
                header
                    .padding(.horizontal, 20)
                    .padding(.top, 8)
                Text("Spark your life.")
                    .font(FlintFont.display(30, relativeTo: .title))
                    .tracking(-0.3)
                    .foregroundStyle(FlintBrand.bone)
                    .padding(.top, 148)
                startPill
                    .padding(.horizontal, 20)
                    .padding(.top, 22)
                statsRow
                    .padding(.horizontal, 20)
                    .padding(.top, 22)
            }
            .frame(maxWidth: .infinity)
        }
        .frame(height: 380)
    }

    private var compactHero: some View {
        VStack(alignment: .leading, spacing: 18) {
            header
            Text("Spark your life.")
                .font(FlintFont.display(30, relativeTo: .title))
                .foregroundStyle(FlintBrand.bone)
                .fixedSize(horizontal: false, vertical: true)
            startPill
            statsRow
        }
        .padding(.horizontal, 20)
        .padding(.top, 10)
    }

    private var header: some View {
        // At accessibility sizes the status pill would truncate beside the wordmark; it takes
        // its own line instead.
        let layout = compact
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8)) : AnyLayout(HStackLayout(spacing: 10))
        return layout {
            HStack(spacing: 8) {
                FlintMark(size: 22)
                Text("Flint")
                    .font(FlintFont.text(20, weight: .bold, relativeTo: .title3))
                    .tracking(-0.3)
                    .foregroundStyle(FlintBrand.bone)
            }
            if !compact { Spacer(minLength: 8) }
            Button(action: onOpenRules) {
                FlintDotPill(text: protectionPill.text, tint: protectionPill.tint,
                             dot: protectionPill.dot, icon: protectionPill.icon)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(protectionPill.text)
            .accessibilityHint("Opens Rules")
        }
    }

    private var startPill: some View {
        Button { showStart = true } label: {
            HStack(spacing: 10) {
                ZStack {
                    Circle()
                        .trim(from: 0, to: 0.83)
                        .stroke(FlintBrand.onEmber, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                        .frame(width: 16, height: 16)
                        .rotationEffect(.degrees(-90))
                    Circle().fill(FlintBrand.onEmber).frame(width: 3.6, height: 3.6)
                        .offset(x: 5.4, y: -5.4)
                }
                .frame(width: 18, height: 18)
                .accessibilityHidden(true)
                Text("Start focus")
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
        }
        .buttonStyle(EmberButtonStyle(height: 52))
    }

    private var statsRow: some View {
        HStack(alignment: .center, spacing: 0) {
            if let focused = focusedToday {
                stat(value: FocusHistoryText.duration(focused), label: "Focused")
            }
            if let resting = restingTargets, !resting.isEmpty, focusedToday != nil {
                Rectangle().fill(FlintBrand.bone(0.10)).frame(width: 1, height: 32)
                restingStat(resting)
            } else if let resting = restingTargets, !resting.isEmpty {
                restingStat(resting)
            }
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(statsSpoken)
    }

    private func stat(value: String, label: String) -> some View {
        VStack(spacing: 5) {
            Text(value)
                .font(FlintFont.display(22, relativeTo: .title2))
                .monospacedDigit()
                .foregroundStyle(FlintBrand.bone)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(label)
                .font(FlintFont.text(10.5, weight: .semibold, relativeTo: .caption2))
                .tracking(1.6)
                .textCase(.uppercase)
                .foregroundStyle(FlintBrand.mist)
        }
        .frame(maxWidth: .infinity)
    }

    private func restingStat(_ targets: [PickedTarget]) -> some View {
        VStack(spacing: 6) {
            HStack(spacing: 4) {
                FlintAppIconStack(targets: targets, size: 22, limit: 3, resting: false)
                if targets.count > 3 {
                    Text("+\(targets.count - 3)")
                        .font(FlintFont.text(12, weight: .semibold))
                        .foregroundStyle(FlintBrand.mist)
                        .padding(.leading, 2)
                }
            }
            .frame(height: 22)
            Text("Resting")
                .font(FlintFont.text(10.5, weight: .semibold, relativeTo: .caption2))
                .tracking(1.6)
                .textCase(.uppercase)
                .foregroundStyle(FlintBrand.mist)
        }
        .frame(maxWidth: .infinity)
    }

    // MARK: Now

    @ViewBuilder private var nowSection: some View {
        if let active = vm.active {
            FlintEmberCard(radius: 24) {
                HStack(spacing: 12) {
                    iconTile(symbol: "lock", active: true)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 5) {
                            Text(active.name)
                                .font(FlintFont.text(15, weight: .semibold))
                                .foregroundStyle(FlintBrand.bone)
                            Text("· now")
                                .font(FlintFont.text(15, weight: .medium))
                                .foregroundStyle(FlintBrand.mist)
                        }
                        Text(sessionLine(active))
                            .flintSecondary(12)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 8)
                    if active.endsAt != nil {
                        FlintStatusPill(text: "\(SessionView.clock(vm.remaining)) left")
                            .accessibilityLabel("\(SessionView.spoken(vm.remaining)) left")
                    }
                }
                if active.endsAt != nil {
                    progressBar(vm.progress)
                }
                restingRow
            }
            .accessibilityElement(children: .contain)
        } else if let current = FlintTodaySchedule.current(in: rules, at: now) {
            FlintEmberCard(radius: 24) {
                HStack(spacing: 12) {
                    iconTile(symbol: "lock", active: true)
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 5) {
                            Text(current.name)
                                .font(FlintFont.text(15, weight: .semibold))
                                .foregroundStyle(FlintBrand.bone)
                            Text("· now")
                                .font(FlintFont.text(15, weight: .medium))
                                .foregroundStyle(FlintBrand.mist)
                        }
                        Text(ruleLine(current))
                            .flintSecondary(12)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 8)
                    if let left = timeLeft(current) {
                        FlintStatusPill(text: "\(left) left")
                    }
                }
                progressBar(occurrenceProgress(current))
                restingRow(targets: current.selection.flintPickedTargets)
            }
            .accessibilityElement(children: .contain)
        } else {
            emptyNowCard
        }
    }

    private var emptyNowCard: some View {
        FlintCard(radius: 24, padding: EdgeInsets(top: 16, leading: 16, bottom: 16, trailing: 16)) {
            VStack(alignment: .leading, spacing: 6) {
                Text(headline)
                    .font(FlintFont.text(16, weight: .semibold))
                    .foregroundStyle(FlintBrand.bone)
                    .fixedSize(horizontal: false, vertical: true)
                Text(subline)
                    .flintSecondary(13)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .accessibilityElement(children: .combine)
        }
    }

    private var restingRow: some View {
        restingRow(targets: restingTargets ?? [])
    }

    private func restingRow(targets: [PickedTarget]) -> some View {
        HStack(spacing: 8) {
            if !targets.isEmpty {
                FlintAppIconStack(targets: targets, size: 36, limit: 5, resting: true)
                if targets.count > 5 {
                    Text("+\(targets.count - 5)")
                        .font(FlintFont.text(12, weight: .semibold))
                        .foregroundStyle(FlintBrand.mist)
                        .frame(width: 36, height: 36)
                        .background(RoundedRectangle(cornerRadius: 11, style: .continuous).fill(FlintBrand.bone(0.06)))
                }
            }
            Spacer(minLength: 8)
            Button(action: onOpenRules) {
                HStack(spacing: 4) {
                    Text("View")
                        .font(FlintFont.text(13, weight: .semibold))
                    Image(systemName: "chevron.right").font(.system(size: 12, weight: .semibold))
                }
                .foregroundStyle(FlintBrand.bone)
                .frame(minHeight: 36)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("View in Rules")
            .accessibilityInputLabels([Text("View"), Text("View in Rules")])
        }
    }

    private func progressBar(_ progress: Double) -> some View {
        GeometryReader { geo in
            ZStack(alignment: .leading) {
                Capsule().fill(FlintBrand.bone(0.10)).frame(height: 4)
                Capsule()
                    .fill(FlintBrand.ember)
                    .frame(width: max(4, geo.size.width * min(1, max(0, progress))), height: 4)
            }
            .frame(maxHeight: .infinity, alignment: .center)
        }
        .frame(height: 4)
        .accessibilityHidden(true)
    }

    private func iconTile(symbol: String, active: Bool) -> some View {
        ZStack {
            Circle().fill(FlintBrand.ember.opacity(active ? 0.14 : 0.08))
            Image(systemName: symbol)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(FlintBrand.ember)
        }
        .frame(width: 36, height: 36)
        .accessibilityHidden(true)
    }

    // MARK: Up next

    @ViewBuilder private var upNext: some View {
        let next = upcoming(limit: 2)
        if !next.isEmpty {
            VStack(alignment: .leading, spacing: 8) {
                HStack(alignment: .firstTextBaseline) {
                    Text("Up next").flintSection()
                    Spacer()
                    Button(action: onOpenRules) {
                        Text("All rules")
                            .font(FlintFont.text(13, weight: .medium))
                            .foregroundStyle(FlintBrand.mist)
                            .frame(minHeight: 32)
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 4)
                ForEach(next, id: \.id) { rule in
                    nextRow(rule)
                }
            }
        }
    }

    private func nextRow(_ rule: FlintScheduleRule) -> some View {
        HStack(spacing: 12) {
            Image(systemName: rule.id == SleepModeController.nightRuleID ? "moon" : "calendar")
                .font(.system(size: 17, weight: .regular))
                .foregroundStyle(FlintBrand.mist)
                .frame(width: 20)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(rule.name)
                    .font(FlintFont.text(14, weight: .semibold))
                    .foregroundStyle(FlintBrand.bone)
                Text(ruleLine(rule))
                    .font(FlintFont.text(12))
                    .foregroundStyle(FlintBrand.mist)
                    .lineLimit(2)
            }
            Spacer(minLength: 8)
            if let cap = nextTimeText(rule) {
                Text(cap)
                    .font(FlintFont.text(12, weight: .semibold))
                    .monospacedDigit()
                    .foregroundStyle(FlintBrand.mist)
            }
        }
        .padding(.horizontal, 14)
        .frame(minHeight: 52)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(FlintBrand.bone(0.04)))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(FlintBrand.bone(0.06)))
        .accessibilityElement(children: .combine)
    }

    private var footerIsEmpty: Bool { vm.errorText == nil && auth.status == .approved }

    // MARK: Footer (authorization / errors)

    @ViewBuilder private var footer: some View {
        VStack(alignment: .leading, spacing: 14) {
            if let message = vm.errorText {
                errorMessage(message)
                    .padding(16)
                    .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(FlintBrand.card))
                    .accessibilityIdentifier("today.session.error")
            }
            if auth.status != .approved {
                grantCard
            }
        }
    }

    private var grantCard: some View {
        VStack(spacing: 12) {
            Text("Flint needs Screen Time access to rest your apps. Nothing leaves this phone.")
                .flintSecondary(14)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("today.authorization.explanation")
            AuthorizationControls(auth: auth, identifierPrefix: "today.authorization")
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 24, style: .continuous).fill(FlintBrand.card))
        .overlay(RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
    }

    private func errorMessage(_ message: String) -> some View {
        Text(message)
            .font(FlintFont.text(13))
            .foregroundStyle(FlintBrand.danger)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
    }

    // MARK: Data

    private var enabledRules: [FlintScheduleRule] { rules.filter(\.enabled) }

    private var hasFailure: Bool {
        health.domains.values.contains { !$0.failures.isEmpty }
    }

    private var protectionPill: (text: String, tint: Color, dot: Color?, icon: String?) {
        if auth.status != .approved {
            return ("Setup needed", FlintBrand.emberText, FlintBrand.ember, "exclamationmark.triangle")
        }
        if hasFailure {
            return ("Needs attention", FlintBrand.emberText, FlintBrand.danger, nil)
        }
        if enabledRules.isEmpty {
            return ("No rules yet", FlintBrand.mist, FlintBrand.stone, nil)
        }
        let count = enabledRules.count
        return ("Protected · \(count) \(count == 1 ? "rule" : "rules")", FlintBrand.bone, FlintBrand.ember, nil)
    }

    private var focusedToday: TimeInterval? {
        guard let summary = history.summary,
              summary.availability == .value || summary.availability == .degraded else { return nil }
        return FocusHistoryText.total(Array(summary.days.suffix(1)))
    }

    private var restingTargets: [PickedTarget]? {
        if vm.active != nil {
            // The session's own frozen targets, not the picker's draft selection.
            let targets = vm.activeSelection?.flintPickedTargets ?? []
            return targets.isEmpty ? nil : targets
        }
        if let current = FlintTodaySchedule.current(in: rules, at: now) {
            let targets = current.selection.flintPickedTargets
            return targets.isEmpty ? nil : targets
        }
        return nil
    }

    private var statsSpoken: String {
        var parts: [String] = []
        if let focused = focusedToday { parts.append("\(FocusHistoryText.duration(focused)) focused today") }
        if let resting = restingTargets, !resting.isEmpty {
            parts.append("\(resting.count) \(resting.count == 1 ? "app" : "apps") resting")
        }
        return parts.isEmpty ? "No focus stats yet" : parts.joined(separator: ", ")
    }

    private func sessionLine(_ active: FlintActiveSession) -> String {
        var parts = [levelName(active.breakLevel)]
        if let endsAt = active.endsAt {
            parts.append("until \(endsAt.formatted(date: .omitted, time: .shortened))")
        }
        if vm.selectionCount > 0 {
            parts.append("\(vm.selectionCount) \(vm.selectionCount == 1 ? "app" : "apps") resting")
        }
        return parts.joined(separator: " · ")
    }

    private func ruleLine(_ rule: FlintScheduleRule) -> String {
        var parts = [levelName(rule.breakLevel)]
        if FlintTodaySchedule.current(in: rules, at: now)?.id == rule.id {
            parts.append("until \(FlintTodaySchedule.clock(rule.schedule.endHour, rule.schedule.endMinute))")
        } else {
            parts.append("\(FlintTodaySchedule.clock(rule.schedule.startHour, rule.schedule.startMinute)) — \(FlintTodaySchedule.clock(rule.schedule.endHour, rule.schedule.endMinute))")
        }
        let count = rule.selection.applicationTokens.count + rule.selection.categoryTokens.count
            + rule.selection.webDomainTokens.count
        if count > 0 {
            parts.append(rule.allowListMode ? "allow list" : "\(count) \(count == 1 ? "item" : "items")")
        }
        return parts.joined(separator: " · ")
    }

    private func levelName(_ level: BreakLevel) -> String {
        switch level {
        case .easy: "Easy"
        case .harder: "Harder"
        case .hardcore: "Hardcore"
        }
    }

    private func timeLeft(_ rule: FlintScheduleRule) -> String? {
        let cal = Calendar.current
        let minute = cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now)
        let end = rule.schedule.endHour * 60 + rule.schedule.endMinute
        var left = end - minute
        if left <= 0 { left += 1440 }
        let h = left / 60, m = left % 60
        if h == 0 { return "\(m)m" }
        return m == 0 ? "\(h)h" : String(format: "%dh %02dm", h, m)
    }

    private func occurrenceProgress(_ rule: FlintScheduleRule) -> Double {
        let cal = Calendar.current
        let minute = Double(cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now))
        var start = Double(rule.schedule.startHour * 60 + rule.schedule.startMinute)
        var end = Double(rule.schedule.endHour * 60 + rule.schedule.endMinute)
        // Overnight window: shift whichever edge lies on the other side of midnight.
        if end <= start { if minute < end { start -= 1440 } else { end += 1440 } }
        let span = end - start
        guard span > 0 else { return 0 }
        return (minute - start) / span
    }

    private func upcoming(limit: Int) -> [FlintScheduleRule] {
        let cal = Calendar.current
        let minute = cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now)
        let weekday = cal.component(.weekday, from: now)
        let currentID = FlintTodaySchedule.current(in: rules, at: now)?.id
        return rules
            .filter {
                $0.enabled && $0.id != currentID && runs($0.schedule, on: weekday)
                    && $0.schedule.startHour * 60 + $0.schedule.startMinute > minute
            }
            .sorted { $0.schedule.startHour * 60 + $0.schedule.startMinute < $1.schedule.startHour * 60 + $1.schedule.startMinute }
            .prefix(limit)
            .map { $0 }
    }

    private func nextTimeText(_ rule: FlintScheduleRule) -> String? {
        let cal = Calendar.current
        let minute = cal.component(.hour, from: now) * 60 + cal.component(.minute, from: now)
        let start = rule.schedule.startHour * 60 + rule.schedule.startMinute
        let delta = start - minute
        guard delta > 0 else { return nil }
        let h = delta / 60, m = delta % 60
        if h == 0 { return "in \(m)m" }
        return m == 0 ? "in \(h)h" : "in \(h)h \(m)m"
    }

    private func runs(_ s: FlintSchedule, on weekday: Int) -> Bool {
        s.daysOfWeek.isEmpty || s.daysOfWeek.contains(weekday)
    }

    private func refreshHistory() {
        lastHistoryRefresh = Date()
        history.refresh()
    }

    // MARK: Copy (real rules only — nothing invented)

    private var headline: String {
        if let current = FlintTodaySchedule.current(in: rules, at: now) {
            switch status(current) {
            case .needsAttention: return "\(current.name) needs attention."
            case .registered: return "\(current.name) is scheduled now."
            default: return "\(current.name) is not verified."
            }
        }
        return "A quiet day so far."
    }

    private var subline: String {
        var parts: [String] = []
        if let current = FlintTodaySchedule.current(in: rules, at: now) {
            parts.append(status(current) == .registered
                ? "Registered with iOS until \(FlintTodaySchedule.clock(current.schedule.endHour, current.schedule.endMinute))"
                : "Open Rules or You to check protection")
        }
        if let next = FlintTodaySchedule.next(in: rules, at: now) {
            parts.append("next, \(next.name) at \(FlintTodaySchedule.clock(next.schedule.startHour, next.schedule.startMinute))")
        }
        if parts.isEmpty { return rules.isEmpty ? "No schedules yet — add one in Rules." : "Nothing else scheduled today." }
        let line = parts.joined(separator: " · ")
        return line.prefix(1).uppercased() + line.dropFirst()
    }

    static func duration(minutes: Int) -> String {
        let h = minutes / 60, m = minutes % 60
        if h == 0 { return "\(m)m" }
        return m == 0 ? "\(h)h" : "\(h)h \(m)m"
    }

    private func status(_ rule: FlintScheduleRule) -> FlintRuleProtectionStatus {
        FlintRuleProtectionStatus.status(enabled: rule.enabled, activityName: rule.monitorName,
            domain: "schedules", health: health, observation: observation, at: now,
            authorized: auth.status == .approved)
    }

    private func refreshHealth() {
        health = FlintGroupStore()?.loadArmingHealth() ?? FlintArmingHealth()
        observation = FlintProtectionObservation.load()
        now = Date()
    }
}

/// Which enabled schedule is running now, and which starts next today.
enum FlintTodaySchedule {
    static func current(in rules: [FlintScheduleRule], at date: Date) -> FlintScheduleRule? {
        let cal = Calendar.current
        let minute = cal.component(.hour, from: date) * 60 + cal.component(.minute, from: date)
        let weekday = cal.component(.weekday, from: date)
        let yesterday = weekday == 1 ? 7 : weekday - 1
        return rules.first { rule in
            guard rule.enabled else { return false }
            let s = rule.schedule
            let start = s.startHour * 60 + s.startMinute, end = s.endHour * 60 + s.endMinute
            if start < end { return runs(s, on: weekday) && minute >= start && minute < end }
            // Overnight: the evening part belongs to today, the morning part to yesterday's run.
            return (runs(s, on: weekday) && minute >= start) || (runs(s, on: yesterday) && minute < end)
        }
    }

    static func next(in rules: [FlintScheduleRule], at date: Date) -> FlintScheduleRule? {
        let cal = Calendar.current
        let minute = cal.component(.hour, from: date) * 60 + cal.component(.minute, from: date)
        let weekday = cal.component(.weekday, from: date)
        return rules
            .filter { $0.enabled && runs($0.schedule, on: weekday) && $0.schedule.startHour * 60 + $0.schedule.startMinute > minute }
            .min { $0.schedule.startHour * 60 + $0.schedule.startMinute < $1.schedule.startHour * 60 + $1.schedule.startMinute }
    }

    static func clock(_ hour: Int, _ minute: Int) -> String { String(format: "%02d:%02d", hour, minute) }

    private static func runs(_ s: FlintSchedule, on weekday: Int) -> Bool {
        s.daysOfWeek.isEmpty || s.daysOfWeek.contains(weekday)
    }
}
