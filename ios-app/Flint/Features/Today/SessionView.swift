import SwiftUI
import FlintCore

/// A running session: the ember travels its arc as the clock runs down, and breath rings
/// pulse around it. The bottom bar holds the only ways out the break level allows.
struct SessionView: View {
    @ObservedObject var vm: SessionViewModel
    @State private var showPass = false
    @State private var passStatus: FlintEmergencyPassStatus?
    @State private var passRefreshID = UUID()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var typeSize

    var body: some View {
        ZStack {
            EmberLandscape(horizonOffset: 8, orbCenter: nil)
            // The arc is laid out on the 390-pt artboard; at accessibility sizes the enlarged
            // countdown and captions sit where the orb passes, so it is left out (as on M02).
            if !typeSize.isAccessibilitySize {
                SunArc(progress: vm.progress)
            }

            if let active = vm.active {
                VStack(spacing: 0) {
                    HStack(spacing: 8) {
                        FlintDotPill(text: active.name, tint: FlintBrand.bone)
                        Spacer(minLength: 8)
                        FlintDotPill(text: levelName(active.breakLevel),
                                     tint: FlintBrand.emberText,
                                     dot: nil,
                                     icon: active.breakLevel == .easy ? "lock.open" : "lock")
                            .accessibilityLabel("\(levelName(active.breakLevel)) break level")
                    }
                    .padding(.top, 8)

                    if active.endsAt != nil {
                        VStack(spacing: 8) {
                            Text(Self.clock(vm.remaining))
                                .flintNumber(84)
                                .accessibilityLabel("\(Self.spoken(vm.remaining)) left")
                            Text(endsLine(active))
                                .flintSecondary(14)
                                .multilineTextAlignment(.center)
                        }
                        // Board: header ends at 92pt, timer at 120pt — keep the ends-at
                        // line clear of the sun's breathing rings.
                        .padding(.top, 16)
                    } else {
                        Text("No time limit").flintTitle(34).padding(.top, 50)
                    }

                    Spacer(minLength: 12)
                    Text("Breathe with the light.")
                        .font(FlintFont.display(17))
                        .foregroundStyle(FlintBrand.softBone)
                        .padding(.bottom, 24)
                    if typeSize.isAccessibilitySize {
                        // Keep every control reachable when text grows.
                        ScrollView(showsIndicators: false) {
                            controlBar(active)
                        }
                        .frame(maxHeight: 360)
                    } else {
                        controlBar(active)
                    }
                }
                .padding(.horizontal, 20)
                .padding(.bottom, 32)
            }

            if showPass {
                EmergencyPassSheet(
                    onKeep: { withAnimation(.easeOut(duration: 0.3)) { showPass = false } },
                    onApplied: {
                        vm.emergencyPassDidApply()
                        showPass = false
                    }
                )
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .fullScreenCover(isPresented: Binding(get: { vm.harderStop != nil }, set: { if !$0 { vm.cancelStop() } })) {
            if let wait = vm.harderStop {
                BreakWaitView(wait: wait, onBack: vm.cancelStop, onStop: vm.confirmStop)
            }
        }
        .fullScreenCover(isPresented: Binding(get: { vm.breakWait != nil }, set: { if !$0 { vm.cancelBreak() } })) {
            if let wait = vm.breakWait {
                BreakWaitView(wait: wait, onBack: vm.cancelBreak, onStop: vm.confirmBreak,
                    finalAction: "Take a 5-minute break", heading: "Harder · Break requested",
                    explanation: vm.breakPreview.message + " The session keeps running. Going back cancels the wait without spending it.",
                    readyExplanation: "Your wait is complete. Confirm up to 5 minutes of access. The session keeps running.")
            }
        }
        .alert("Take a 5-minute break", isPresented: $vm.confirmingBreak) {
            Button("Cancel", role: .cancel, action: vm.cancelBreak)
            Button("Take a 5-minute break", action: vm.confirmBreak)
        } message: { Text(vm.breakPreview.message) }
        .preferredColorScheme(.dark)
        .onAppear { refreshPassStatus(); vm.refreshBreakStatus() }
        .onChange(of: scenePhase) { phase in if phase == .active { refreshPassStatus(); vm.refreshBreakStatus() } }
        .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in refreshPassStatus() }
        .onReceive(Timer.publish(every: 15, on: .main, in: .common).autoconnect()) { _ in refreshPassStatus() }
    }

    private func controlBar(_ active: FlintActiveSession) -> some View {
            VStack(alignment: .leading, spacing: 12) {
                // What is resting: real icons for the running session's own frozen selection.
                if let resting = vm.activeSelection, SessionViewModel.count(of: resting) > 0 {
                    let restingCount = SessionViewModel.count(of: resting)
                    HStack(spacing: 8) {
                        FlintAppIconStack(targets: resting.flintPickedTargets, size: 32,
                                          border: Color(hex: 0x161518), limit: 5, resting: true)
                        if restingCount > 5 {
                            Text("+\(restingCount - 5)")
                                .font(FlintFont.text(11, weight: .semibold))
                                .foregroundStyle(FlintBrand.mist)
                                .frame(width: 32, height: 32)
                                .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(FlintBrand.bone(0.07)))
                        }
                        Spacer(minLength: 8)
                        Text("\(SessionViewModel.summary(of: resting)) resting")
                            .font(FlintFont.text(12))
                            .foregroundStyle(FlintBrand.mist)
                    }
                    .accessibilityElement(children: .combine)
                }

                Rectangle().fill(FlintBrand.bone(0.08)).frame(height: 1)

                HStack(alignment: .center, spacing: 12) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(levelLine(active.breakLevel))
                            .font(FlintFont.text(14, weight: .semibold))
                            .foregroundStyle(FlintBrand.bone)
                        Text(levelDetail(active.breakLevel))
                            .font(FlintFont.text(12))
                            .foregroundStyle(FlintBrand.mist)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    Spacer(minLength: 8)
                    Button {
                        refreshPassStatus()
                        withAnimation(.easeOut(duration: 0.3)) { showPass = true }
                    } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "key.viewfinder").font(.system(size: 13, weight: .regular))
                            Text(passStatus?.isAvailable == true ? "Pass · 1" : "Pass")
                                .font(FlintFont.text(12, weight: .semibold))
                        }
                    }
                    .buttonStyle(GhostButtonStyle(height: 40))
                    .accessibilityLabel("Emergency Pass, \(passStatus?.label ?? "protection state unavailable")")
                }

                breakStatusPreview(active)
                breakRow(active)
                if let error = vm.errorText {
                    Text(error).flintSecondary(12).accessibilityIdentifier("session.action.error")
                }
                if let notice = vm.breakNotice {
                    HStack {
                        Text(notice).flintSecondary(12)
                        Button("Dismiss", action: vm.acknowledgeBreakNotice).buttonStyle(GhostButtonStyle(height: 36))
                    }
                }
            }
            .padding(14)
            .background(
                RoundedRectangle(cornerRadius: 26, style: .continuous).fill(.ultraThinMaterial)
                    .environment(\.colorScheme, .dark)
                    .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous)
                        .fill(Color(hex: 0x181618).opacity(0.72)))
            )
            .overlay(RoundedRectangle(cornerRadius: 26, style: .continuous).strokeBorder(FlintBrand.bone(0.10)))
    }

    private func endsLine(_ active: FlintActiveSession) -> String {
        var parts: [String] = []
        if let endsAt = active.endsAt {
            parts.append("ends at \(endsAt.formatted(date: .omitted, time: .shortened))")
        }
        if let planned = active.plannedDuration {
            let minutes = Int((planned / 60).rounded())
            parts.append("\(minutes) min session")
        }
        return parts.joined(separator: " · ")
    }

    private func levelDetail(_ level: BreakLevel) -> String {
        switch level {
        case .easy: "You can stop whenever you like."
        case .harder: "Each early stop waits a few minutes first."
        case .hardcore: "Flint can’t be deleted while Hardcore runs."
        }
    }

    @ViewBuilder private func breakStatusPreview(_ active: FlintActiveSession) -> some View {
        if vm.resumingBreak {
            Text("Applying protection").flintSecondary(12)
        } else if vm.ordinaryBreak != nil {
            HStack {
                Text(vm.ordinaryBreak?.applicationPending == true ? "Applying break" : "Break · \(Self.clock(vm.breakRemaining)) left")
                    .flintSecondary(12)
                Spacer(minLength: 8)
                Button("Resume now", action: vm.resumeBreakNow).buttonStyle(GhostButtonStyle(height: 36))
            }
        } else if vm.breakPreview.isActionable {
            EmptyView()
        } else {
            Text(vm.breakPreview.message).flintSecondary(12)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    /// The second row: break request (Harder) or end early (Easy/Harder).
    @ViewBuilder private func breakRow(_ active: FlintActiveSession) -> some View {
        HStack(spacing: 8) {
            if vm.breakPreview.isActionable {
                Button(action: vm.requestBreak) {
                    Text("Take a 5-minute break")
                }
                .buttonStyle(GhostButtonStyle(height: 40))
                .accessibilityLabel("Take a 5-minute break")
                .accessibilityHint(vm.breakPreview.message)
            }
            Spacer(minLength: 8)
            if active.breakLevel != .hardcore {
                Button("End early", action: vm.stop)
                    .buttonStyle(GhostButtonStyle(height: 40))
                    .accessibilityHint(vm.stopPreview)
            } else {
                Text(vm.stopPreview)
                    .font(FlintFont.text(12))
                    .foregroundStyle(FlintBrand.mist)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func refreshPassStatus() {
        let request = UUID(); passRefreshID = request
        Task {
            let result = await FlintEmergencyPassController.displayStatus()
            guard passRefreshID == request else { return }
            passStatus = try? result.get()
        }
    }

    private func levelName(_ level: BreakLevel) -> String {
        switch level {
        case .easy: "Easy"
        case .harder: "Harder"
        case .hardcore: "Hardcore"
        }
    }

    private func levelLine(_ level: BreakLevel) -> String {
        switch level {
        case .easy: "Stop any time"
        case .harder: "Stopping takes a short wait"
        case .hardcore: "Locked until it ends"
        }
    }

    static func clock(_ t: TimeInterval) -> String {
        let total = Int(t.rounded())
        let h = total / 3600, m = (total % 3600) / 60, s = total % 60
        return h > 0 ? String(format: "%d:%02d:%02d", h, m, s) : String(format: "%02d:%02d", m, s)
    }

    static func spoken(_ t: TimeInterval) -> String {
        let total = Int(t.rounded())
        let h = total / 3600, m = (total % 3600) / 60
        return h > 0 ? "\(h) hours \(m) minutes" : "\(m) minutes"
    }
}

/// The sun's path across a session: a dotted arc, the travelled part drawn solid, and the
/// ember (with its breath rings) at the current point. Reference geometry: E06-Session.
struct SunArc: View {
    let progress: Double
    var showsRings = true
    var complete = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var breathe = false

    // Apex control at y 140 (was 90): mid-session the orb rose behind the "ends at" caption,
    // grey on ember. At 140 its top edge stays clear of the caption at t = 0.5.
    private let p0 = CGPoint(x: 50, y: 470), p1 = CGPoint(x: 195, y: 140), p2 = CGPoint(x: 340, y: 470)

    var body: some View {
        GeometryReader { geo in
            let s = geo.size.width / 390
            let t = complete ? 1 : progress
            let point = bezier(t)
            ZStack(alignment: .topLeading) {
                Canvas { ctx, _ in
                    ctx.scaleBy(x: s, y: s)
                    var full = Path()
                    full.move(to: p0); full.addQuadCurve(to: p2, control: p1)
                    if complete {
                        ctx.stroke(full, with: .color(FlintBrand.bone(0.35)), lineWidth: 1.2)
                    } else {
                        ctx.stroke(full, with: .color(FlintBrand.bone(0.22)),
                                   style: StrokeStyle(lineWidth: 1, lineCap: .round, dash: [1, 7]))
                        var done = Path()
                        let c = CGPoint(x: p0.x + (p1.x - p0.x) * t, y: p0.y + (p1.y - p0.y) * t)
                        done.move(to: p0); done.addQuadCurve(to: point, control: c)
                        ctx.stroke(done, with: .color(FlintBrand.bone(0.5)), style: StrokeStyle(lineWidth: 1.2, lineCap: .round))
                        ctx.fill(Path(ellipseIn: CGRect(x: p2.x - 3, y: p2.y - 3, width: 6, height: 6)), with: .color(FlintBrand.bone(0.4)))
                    }
                }
                if showsRings && !complete {
                    ForEach([78.0, 104.0], id: \.self) { r in
                        Circle()
                            .stroke(FlintBrand.flame, lineWidth: r > 90 ? 0.8 : 1)
                            .frame(width: r * 2 * s, height: r * 2 * s)
                            .scaleEffect(reduceMotion ? 1 : (breathe ? 1.18 : 0.82))
                            .opacity(reduceMotion ? 0.4 : (breathe ? 0.6 : 0.25))
                            .animation(reduceMotion ? nil : .easeInOut(duration: 4).repeatForever(autoreverses: true)
                                .delay(r > 90 ? 1 : 0), value: breathe)
                            .position(x: point.x * s, y: point.y * s)
                    }
                }
                EmberOrb(radius: (complete ? 40 : 44) * s, haloRadius: (complete ? 170 : 150) * s)
                    .position(x: point.x * s, y: (complete ? point.y - 8 : point.y) * s)
            }
        }
        .ignoresSafeArea()
        .accessibilityHidden(true)
        .onAppear { breathe = true }
    }

    private func bezier(_ t: Double) -> CGPoint {
        let u = 1 - t
        return CGPoint(x: u * u * p0.x + 2 * u * t * p1.x + t * t * p2.x,
                       y: u * u * p0.y + 2 * u * t * p1.y + t * t * p2.y)
    }
}

/// Harder's friction: a breathing wait before an early stop. If the urge passes, go back.
/// The wait itself is persisted by Flint (1, then 5, then 15 minutes for repeated early stops
/// of the same session, ADR-012 §4) — closing this screen or the app doesn't reset it, and going
/// back cancels it without counting.
struct BreakWaitView: View {
    let wait: SessionViewModel.HarderStop
    let onBack: () -> Void
    let onStop: () -> Void
    var finalAction = "End early"
    var heading = "Harder · End early requested"
    var explanation = "After the wait, confirm End early. If the urge has passed, go back — that doesn't count against you."
    var readyExplanation: String?

    @State private var now = Date()
    @State private var breathe = false
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    private var total: TimeInterval { max(1, wait.total) }

    private var left: TimeInterval {
        switch wait {
        case let .waiting(until, _): return max(0, until.timeIntervalSince(now))
        case .ready: return 0
        }
    }

    var body: some View {
        ZStack {
            FlintBrand.night.ignoresSafeArea()
            GeometryReader { geometry in
            ScrollView(showsIndicators: false) {
            VStack(spacing: 0) {
                Text(heading)
                    .flintEyebrow()
                    .padding(.horizontal, 12)
                    .frame(minHeight: 30)
                    .background(Capsule().fill(FlintBrand.bone(0.08)))
                    .overlay(Capsule().strokeBorder(FlintBrand.bone(0.10)))
                    .padding(.top, 24)
                Text("Breathe while you wait.")
                    .flintTitle(30)
                    .multilineTextAlignment(.center)
                    .padding(.top, 18)
                    .padding(.horizontal, 20)
                Text(left == 0 ? (readyExplanation ?? explanation) : explanation)
                    .flintSecondary(15)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .frame(maxWidth: 300)
                    .padding(.top, 10)
                    .fixedSize(horizontal: false, vertical: true)

                Spacer(minLength: 24)
                ZStack {
                    Circle()
                        .fill(RadialGradient(colors: [FlintBrand.ember.opacity(0.28), FlintBrand.ember.opacity(0.06),
                                                      FlintBrand.ember.opacity(0)],
                                             center: .center, startRadius: 0, endRadius: 130))
                        .frame(width: 280, height: 280)
                        .scaleEffect(reduceMotion ? 0.9 : (breathe ? 1 : 0.78))
                    Circle().stroke(FlintBrand.bone(0.12), lineWidth: 2).frame(width: 256, height: 256)
                    Circle()
                        .trim(from: 0, to: 1 - left / total)
                        .stroke(FlintBrand.ember, style: StrokeStyle(lineWidth: 3, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .frame(width: 256, height: 256)
                    VStack(spacing: 8) {
                        Text(SessionView.clock(left)).flintNumber(64)
                        Text(reduceMotion ? "breathe slowly" : (breathe ? "breathe out" : "breathe in")).flintSecondary()
                    }
                }
                .frame(width: 280, height: 280)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(left > 0 ? "\(Int(left.rounded())) seconds until \(finalAction)" : "\(finalAction) is ready to confirm")
                Spacer(minLength: 24)

                VStack(spacing: 8) {
                    Button("I’m good, go back", action: onBack).buttonStyle(BoneButtonStyle())
                    Button(left > 0 ? "\(finalAction) · in \(SessionView.clock(left))" : finalAction, action: onStop)
                        .buttonStyle(QuietButtonStyle())
                        .overlay(Capsule().strokeBorder(FlintBrand.bone(0.12)))
                        .disabled(left > 0)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 36)
            .frame(minHeight: geometry.size.height)
            }
            }
        }
        .onReceive(Timer.publish(every: 1, on: .main, in: .common).autoconnect()) { now = $0 }
        .onAppear {
            guard !reduceMotion else { return }
            withAnimation(.easeInOut(duration: 4).repeatForever(autoreverses: true)) { breathe = true }
        }
        .preferredColorScheme(.dark)
    }
}

/// Rounded top corners only (iOS 16 has no `UnevenRoundedRectangle`).
struct UnevenTop: Shape {
    let radius: CGFloat
    func path(in rect: CGRect) -> Path {
        Path(UIBezierPath(roundedRect: rect, byRoundingCorners: [.topLeft, .topRight],
                          cornerRadii: CGSize(width: radius, height: radius)).cgPath)
    }
}

/// The ember has set: a session ran to its end.
struct SessionCompleteView: View {
    @ObservedObject private var history = FocusHistoryModel.shared
    let finished: SessionViewModel.Finished
    let onDone: () -> Void
    let onAgain: () -> Void

    var body: some View {
        ZStack {
            EmberLandscape(horizonOffset: 8, orbCenter: nil, showsMist: false)
            SunArc(progress: 1, showsRings: false, complete: true)
            GeometryReader { geometry in
            ScrollView(showsIndicators: false) {
            VStack(spacing: 0) {
                Text("\(finished.name) · Complete")
                    .flintEyebrow(FlintBrand.mist)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 30)
                    .background(Capsule().fill(FlintBrand.bone(0.08)))
                    .overlay(Capsule().strokeBorder(FlintBrand.bone(0.10)))
                    .padding(.top, 48)

                Text("The ember has set.")
                    .flintTitle(34)
                    .padding(.top, 18)
                Text(completeLine)
                    .flintSecondary(15)
                    .padding(.top, 10)
                if finished.isPreview { Text("DEBUG · Seeded preview events").flintSecondary(12).padding(.top, 8) }
                if finished.historyDegraded { Text(FocusHistoryText.degraded).flintSecondary(13).padding(.top, 8) }

                Spacer(minLength: 24)
                stats
                // Board: stats sit below the ridges, just above the actions, clear of the set sun.
                Spacer(minLength: 16).frame(maxHeight: 28)

                VStack(spacing: 8) {
                    Button("Done", action: onDone).buttonStyle(BoneButtonStyle())
                    Button("Go again", action: onAgain).buttonStyle(QuietButtonStyle())
                        .frame(minHeight: 48)
                }
            }
            .multilineTextAlignment(.center)
            .padding(.horizontal, 20)
            .padding(.bottom, 36)
            .frame(minHeight: geometry.size.height)
            }
            }
        }
        .preferredColorScheme(.dark)
    }

    private var completeLine: String {
        let minutes = Int((finished.focusedSeconds / 60).rounded())
        if minutes >= 60 {
            let h = minutes / 60, m = minutes % 60
            return m == 0 ? "\(h) hours, start to finish." : "\(h) hours \(m) minutes, start to finish."
        }
        return "\(minutes) \(minutes == 1 ? "minute" : "minutes"), start to finish."
    }

    private var stats: some View {
        HStack(spacing: 8) {
            statCard(value: FocusHistoryText.duration(finished.todaySeconds), label: "Today")
            statCard(value: FocusHistoryText.duration(finished.focusedSeconds), label: "This session")
        }
        .accessibilityElement(children: .combine)
    }

    private func statCard(value: String, label: String) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(FlintFont.display(24, relativeTo: .title2))
                .monospacedDigit()
                .foregroundStyle(FlintBrand.bone)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(label)
                .font(FlintFont.text(11, weight: .semibold, relativeTo: .caption2))
                .tracking(1.4)
                .textCase(.uppercase)
                .foregroundStyle(FlintBrand.mist)
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous)
            .fill(Color(hex: 0x181618).opacity(0.7)))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(FlintBrand.bone(0.10)))
    }
}
