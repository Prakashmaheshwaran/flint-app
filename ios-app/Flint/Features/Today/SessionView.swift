import SwiftUI
import FlintCore

/// A running session: the ember travels its arc as the clock runs down, and breath rings
/// pulse around it. The bottom bar holds the only ways out the break level allows.
struct SessionView: View {
    @ObservedObject var vm: SessionViewModel
    @State private var showPass = false
    @State private var passStatus: FlintEmergencyPassStatus?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        ZStack {
            EmberLandscape(horizonOffset: 8, orbCenter: nil)
            SunArc(progress: vm.progress)

            if let active = vm.active {
                VStack(spacing: 0) {
                    HStack {
                        Text(active.name).flintEyebrow()
                        Spacer()
                        if vm.selectionCount > 0 { Text("\(vm.selectionSummary) resting").flintEyebrow() }
                    }
                    .padding(.top, 18)

                    if active.endsAt != nil {
                        Text(Self.clock(vm.remaining))
                            .flintNumber(84)
                            .padding(.top, 34)
                            .accessibilityLabel("\(Self.spoken(vm.remaining)) left")
                        if let endsAt = active.endsAt {
                            Text("ends at \(endsAt.formatted(date: .omitted, time: .shortened))")
                                .flintSecondary(14)
                                .padding(.top, 10)
                        }
                    } else {
                        Text("No time limit").flintTitle(34).padding(.top, 40)
                    }

                    Spacer()
                    Text("Breathe with the light.")
                        .font(FlintFont.display(17))
                        .foregroundStyle(FlintBrand.softBone)
                        .padding(.bottom, 84)
                    controlBar(active)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 12)
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
        .preferredColorScheme(.dark)
        .onAppear(perform: refreshPassStatus)
        .onChange(of: scenePhase) { phase in if phase == .active { refreshPassStatus() } }
        .onReceive(NotificationCenter.default.publisher(for: .flintProtectionReconciled)) { _ in refreshPassStatus() }
        .onReceive(Timer.publish(every: 15, on: .main, in: .common).autoconnect()) { _ in refreshPassStatus() }
    }

    private func controlBar(_ active: FlintActiveSession) -> some View {
        VStack(spacing: 12) {
            HStack(spacing: 12) {
                Image(systemName: active.breakLevel == .easy ? "lock.open" : "lock")
                    .font(.system(size: 17))
                    .foregroundStyle(FlintBrand.mist)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(levelName(active.breakLevel)).font(FlintFont.text(14, weight: .medium)).foregroundStyle(FlintBrand.bone)
                    Text(levelLine(active.breakLevel)).font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
                }
                Spacer(minLength: 0)
                if active.breakLevel != .hardcore {
                    Button("End session") { vm.stop() }.buttonStyle(GhostButtonStyle())
                }
            }
            Button {
                refreshPassStatus()
                withAnimation(.easeOut(duration: 0.3)) { showPass = true }
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text("Emergency Pass").font(FlintFont.text(14, weight: .medium)).foregroundStyle(FlintBrand.bone)
                    Text(passStatus?.label ?? "Protection state unavailable")
                        .font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
                    Text("One pass per rolling 7 days after use · global recovery up to 60 minutes")
                        .font(FlintFont.text(12)).foregroundStyle(FlintBrand.mist)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .fixedSize(horizontal: false, vertical: true)
            }
            .buttonStyle(GhostButtonStyle())
        }
        .padding(EdgeInsets(top: 12, leading: 18, bottom: 12, trailing: 12))
        .background(
            RoundedRectangle(cornerRadius: 28, style: .continuous).fill(.ultraThinMaterial)
                .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).fill(Color(red: 24 / 255, green: 22 / 255, blue: 24 / 255).opacity(0.68)))
        )
        .overlay(RoundedRectangle(cornerRadius: 28, style: .continuous).strokeBorder(FlintBrand.bone(0.10)))
    }

    private func refreshPassStatus() {
        passStatus = try? FlintEmergencyPassController().status().get()
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

    private let p0 = CGPoint(x: 50, y: 470), p1 = CGPoint(x: 195, y: 90), p2 = CGPoint(x: 340, y: 470)

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
            VStack(spacing: 0) {
                Text("Harder · Stop requested").flintEyebrow().padding(.top, 60)
                Text("Breathe while you wait.")
                    .flintTitle(30)
                    .multilineTextAlignment(.center)
                    .padding(.top, 18)
                Text("Ending early opens when the ring closes. If the urge has passed by then, go back — that doesn't count against you.")
                    .flintSecondary(15)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .frame(maxWidth: 290)
                    .padding(.top, 10)

                Spacer()
                ZStack {
                    Circle()
                        .fill(RadialGradient(colors: [FlintBrand.ember.opacity(0.35), FlintBrand.ember.opacity(0.04)],
                                             center: .center, startRadius: 0, endRadius: 118))
                        .frame(width: 236, height: 236)
                        .scaleEffect(reduceMotion ? 0.9 : (breathe ? 1 : 0.78))
                    Circle().stroke(FlintBrand.bone(0.12), lineWidth: 1.2).frame(width: 256, height: 256)
                    Circle()
                        .trim(from: 0, to: 1 - left / total)
                        .stroke(FlintBrand.ember, style: StrokeStyle(lineWidth: 2, lineCap: .round))
                        .rotationEffect(.degrees(-90))
                        .frame(width: 256, height: 256)
                    VStack(spacing: 8) {
                        Text(SessionView.clock(left)).flintNumber(64)
                        Text(reduceMotion ? "breathe slowly" : (breathe ? "breathe out" : "breathe in")).flintSecondary()
                    }
                }
                .frame(width: 280, height: 280)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(left > 0 ? "\(Int(left.rounded())) seconds until you can end the session" : "You can end the session now")
                Spacer()

                VStack(spacing: 8) {
                    Button("I’m good, go back", action: onBack).buttonStyle(BoneButtonStyle())
                    Button(left > 0 ? "End session · in \(SessionView.clock(left))" : "End session", action: onStop)
                        .buttonStyle(QuietButtonStyle())
                        .overlay(Capsule().strokeBorder(FlintBrand.bone(0.12)))
                        .disabled(left > 0)
                }
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 12)
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
    let finished: SessionViewModel.Finished
    let onDone: () -> Void
    let onAgain: () -> Void

    var body: some View {
        ZStack {
            EmberLandscape(horizonOffset: 8, orbCenter: nil, showsMist: false)
            SunArc(progress: 1, showsRings: false, complete: true)
            VStack(spacing: 0) {
                Text("\(finished.name) · Complete").flintEyebrow().padding(.top, 52)
                Text("The ember has set.").flintTitle(34).padding(.top, 18)
                Text("\(FocusHistoryText.duration(finished.focusedSeconds)) focused in this session.").flintSecondary(15).padding(.top, 10)
                if finished.isPreview { Text("DEBUG · Seeded preview events").flintSecondary(12).padding(.top, 8) }
                if finished.historyDegraded { Text(FocusHistoryText.degraded).flintSecondary(13).padding(.top, 8) }
                Spacer()
                VStack(spacing: 6) {
                    Text(FocusHistoryText.duration(finished.todaySeconds)).flintNumber(36)
                    Text("Focused today").flintEyebrow()
                }
                .padding(.vertical, 24)
                .accessibilityElement(children: .combine)
                VStack(spacing: 8) {
                    Button("Done", action: onDone).buttonStyle(BoneButtonStyle())
                    Button("Start another", action: onAgain).buttonStyle(QuietButtonStyle())
                }
            }
            .multilineTextAlignment(.center)
            .padding(.horizontal, 24)
            .padding(.bottom, 12)
        }
        .preferredColorScheme(.dark)
    }
}
