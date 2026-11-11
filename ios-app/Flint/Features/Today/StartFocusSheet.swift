import SwiftUI
import FamilyControls
import FlintCore

/// The Start focus sheet: how long, what rests (Apple's picker), how hard it is to leave.
/// M04: minutes dial, presets, the app card and the one ember action.
struct StartFocusSheet: View {
    @ObservedObject var vm: SessionViewModel
    let onClose: () -> Void

    @State private var showPicker = false
    @State private var showSaveGroup = false
    @State private var groupName = ""
    @Environment(\.dynamicTypeSize) private var typeSize

    private let durations = [25, 45, 60, 90]

    var body: some View {
        ZStack {
            FlintBrand.ground.ignoresSafeArea()
            VStack(alignment: .leading, spacing: 0) {
                FlintSheetGrabber()

                HStack(alignment: .center) {
                    Text("Start focus")
                        .flintTitle(32)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 12)
                    Button(action: onClose) {
                        Image(systemName: "xmark").font(.system(size: 15, weight: .semibold))
                    }
                    .buttonStyle(CircleIconButtonStyle(size: 44, filled: true))
                    .accessibilityLabel("Close")
                }
                .padding(.top, 10)

                ScrollView(showsIndicators: false) {
                    VStack(alignment: .leading, spacing: 0) {
                        modeToggle.padding(.top, 24)
                        if vm.isPomodoro {
                            pomodoroSection.padding(.top, 14)
                        } else {
                            durationPicker.padding(.top, 18)
                            presets.padding(.top, 18)
                        }
                        blockRow.padding(.top, 22)
                        if !vm.savedGroups.isEmpty || vm.selectionCount > 0 { groupChips.padding(.top, 12) }
                        Text("Break level").flintSection().padding(.top, 26).padding(.horizontal, 4)
                        breakLevels.padding(.top, 10)
                        Text(note(for: vm.breakLevel))
                            .flintSecondary(13)
                            .fixedSize(horizontal: false, vertical: true)
                            .lineSpacing(3)
                            .padding(.horizontal, 4)
                            .padding(.top, 10)
                        if typeSize.isAccessibilitySize { footer.padding(.top, 18) }
                    }
                }

                if !typeSize.isAccessibilitySize { footer.padding(.top, 12) }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 16)
        }
        .presentationDragIndicator(.hidden)
        .familyActivityPicker(isPresented: $showPicker, selection: $vm.selection)
        .onChange(of: vm.selection) { _ in vm.persistSelection() }
        .confirmationDialog(vm.breakLevel == .hardcore ? "Start a Hardcore block?" : "Replace the running session?",
                            isPresented: $vm.confirmingHardcore,
                            titleVisibility: .visible) {
            Button(vm.breakLevel == .hardcore ? "Start Hardcore" : "Replace") {
                vm.start(confirmed: true)
                if vm.active != nil { onClose() }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            if vm.breakLevel == .hardcore {
                if let plan = vm.pomodoroPlan {
                    Text("This is one commitment: \(plan.rounds) rounds of \(plan.focusMinutes) minutes with planned \(plan.restMinutes)-minute rests — \(plan.totalMinutes) minutes. You can't end it early or skip a rest. It ends on its own, or with your Emergency Pass.")
                } else {
                    Text("You can't end it early. It ends after \(vm.committedMinutes) minutes, or with your Emergency Pass.")
                }
            } else if let plan = vm.pomodoroPlan {
                Text("The session that's running now ends and this \(plan.totalMinutes)-minute Pomodoro chain takes its place. Its planned rests are part of the commitment.")
            } else {
                Text("The session that's running now ends and this one takes its place.")
            }
        }
        .alert("Save as group", isPresented: $showSaveGroup) {
            TextField("Name", text: $groupName)
            Button("Save") { vm.saveSelectionAsGroup(name: groupName); groupName = "" }
            Button("Cancel", role: .cancel) { groupName = "" }
        }
        .preferredColorScheme(.dark)
    }

    private var footer: some View {
        VStack(spacing: 8) {
            Button {
                vm.start()
                if vm.active != nil { onClose() }
            } label: {
                Text(vm.isPomodoro ? "Begin Pomodoro · \(vm.committedMinutes) min" : "Begin \(vm.committedMinutes) minutes")
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 12)
            }
            .buttonStyle(EmberButtonStyle())
            .disabled(vm.selectionCount == 0 || (vm.isPomodoro && vm.pomodoroPlan == nil))
            if let error = vm.errorText {
                Text(error)
                    .font(FlintFont.text(13))
                    .foregroundStyle(FlintBrand.danger)
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                if vm.needsProtectionResume {
                    Button("Resume protection now", action: vm.resumeProtectionNow).buttonStyle(QuietButtonStyle())
                }
            } else if vm.selectionCount == 0 {
                Text("Choose what rests first.")
                    .flintSecondary()
                    .frame(maxWidth: .infinity)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    // MARK: Duration (M04)

    private var durationPicker: some View {
        VStack(spacing: 16) {
            HStack {
                lessTime
                Spacer(minLength: 8)
                durationValue
                Spacer(minLength: 8)
                moreTime
            }
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 8),
                                     count: typeSize.isAccessibilitySize ? 2 : 4), spacing: 8) {
                ForEach(durations, id: \.self) { minutes in
                    Button("\(minutes)") { vm.durationMinutes = minutes }
                        .buttonStyle(ChipButtonStyle(selected: vm.durationMinutes == minutes, height: 40, stretch: true))
                        .accessibilityLabel("\(minutes) minutes")
                        .accessibilityAddTraits(vm.durationMinutes == minutes ? .isSelected : [])
                }
            }
            .padding(.horizontal, 4)
        }
    }

    private var durationValue: some View {
        VStack(spacing: 2) {
            Text("\(vm.durationMinutes)")
                .font(FlintFont.display(88, relativeTo: .largeTitle))
                .tracking(-2)
                .monospacedDigit()
                .foregroundStyle(FlintBrand.bone)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text("MINUTES")
                .font(FlintFont.text(12, weight: .semibold, relativeTo: .caption))
                .tracking(2)
                .foregroundStyle(FlintBrand.mist)
        }
        .fixedSize()
        .accessibilityElement(children: .combine)
    }

    private var lessTime: some View {
        Button { vm.durationMinutes = max(5, vm.durationMinutes - 5) } label: {
            Image(systemName: "minus").font(.system(size: 17, weight: .medium))
        }
        .buttonStyle(CircleIconButtonStyle(size: 52))
        .accessibilityLabel("Five minutes less")
    }

    private var moreTime: some View {
        Button { vm.durationMinutes = min(240, vm.durationMinutes + 5) } label: {
            Image(systemName: "plus").font(.system(size: 17, weight: .medium))
        }
        .buttonStyle(CircleIconButtonStyle(size: 52))
        .accessibilityLabel("Five minutes more")
    }

    // MARK: Presets (M04)

    private var presets: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(presetChoices, id: \.name) { preset in
                    Button {
                        vm.durationMinutes = preset.minutes
                    } label: {
                        HStack(spacing: 5) {
                            Text(preset.name)
                            Text(minutesLabel(preset.minutes)).foregroundStyle(FlintBrand.mist)
                        }
                        .fixedSize()
                    }
                    .buttonStyle(ChipButtonStyle(selected: vm.durationMinutes == preset.minutes, height: 38))
                    .accessibilityLabel("\(preset.name), \(preset.minutes) minutes")
                }
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 2)
        }
        .scrollClipDisabledIfAvailable()
    }

    private let presetChoices: [(name: String, minutes: Int)] = [("Deep study", 90), ("Commute", 30),
                                                           ("Workout", 60), ("Reading", 30)]

    // MARK: Pomodoro (F01)

    private var modeToggle: some View {
        HStack(spacing: 8) {
            Button("Focus") { vm.isPomodoro = false }
                .buttonStyle(ChipButtonStyle(selected: !vm.isPomodoro, height: 40, stretch: true))
                .accessibilityLabel("Single focus session")
                .accessibilityAddTraits(!vm.isPomodoro ? .isSelected : [])
            Button("Pomodoro") { vm.isPomodoro = true }
                .buttonStyle(ChipButtonStyle(selected: vm.isPomodoro, height: 40, stretch: true))
                .accessibilityLabel("Pomodoro focus and rest chain")
                .accessibilityAddTraits(vm.isPomodoro ? .isSelected : [])
        }
        .padding(.horizontal, 4)
    }

    private var pomodoroSection: some View {
        VStack(alignment: .leading, spacing: 12) {
            pomodoroRow("Focus", value: vm.pomodoroFocusMinutes, unit: "min", range: 5...120, step: 5) {
                vm.pomodoroFocusMinutes = $0
            }
            pomodoroRow("Rest", value: vm.pomodoroRestMinutes, unit: "min", range: 1...60, step: 1) {
                vm.pomodoroRestMinutes = $0
                if let long = vm.pomodoroLongRestMinutes, long < $0 { vm.pomodoroLongRestMinutes = $0 }
            }
            pomodoroRow("Rounds", value: vm.pomodoroRounds, unit: "", range: 2...8, step: 1) {
                vm.pomodoroRounds = $0
                // A long rest needs a round it can follow: every 2...(rounds - 1).
                if $0 < 3 {
                    vm.pomodoroLongRestMinutes = nil
                    vm.pomodoroLongRestEvery = nil
                } else if let every = vm.pomodoroLongRestEvery, every > $0 - 1 {
                    vm.pomodoroLongRestEvery = max(2, $0 - 1)
                }
            }
            if vm.pomodoroRounds > 2 { longRestControls }
            Text(pomodoroSummary)
                .flintSecondary(12)
                .fixedSize(horizontal: false, vertical: true)
            Text("Rests are planned and part of this commitment: they can't be skipped, shortened or extended. Your break level applies to the whole chain, and stopping ends the remaining rounds.")
                .flintSecondary(12)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityIdentifier("pomodoro.disclosure")
        }
        .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16))
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(FlintBrand.card))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
    }

    private func pomodoroRow(_ title: String, value: Int, unit: String, range: ClosedRange<Int>,
                             step: Int, set: @escaping (Int) -> Void) -> some View {
        HStack(spacing: 12) {
            Text(title).font(FlintFont.text(15, weight: .semibold)).foregroundStyle(FlintBrand.bone)
            Spacer(minLength: 8)
            Button { set(max(range.lowerBound, value - step)) } label: {
                Image(systemName: "minus").font(.system(size: 13, weight: .semibold))
            }
            .buttonStyle(CircleIconButtonStyle(size: 36))
            .accessibilityLabel("Less \(title.lowercased())")
            Text(unit.isEmpty ? "\(value)" : "\(value) \(unit)")
                .font(FlintFont.text(16, weight: .semibold))
                .monospacedDigit()
                .frame(minWidth: 64)
                .foregroundStyle(FlintBrand.bone)
            Button { set(min(range.upperBound, value + step)) } label: {
                Image(systemName: "plus").font(.system(size: 13, weight: .semibold))
            }
            .buttonStyle(CircleIconButtonStyle(size: 36))
            .accessibilityLabel("More \(title.lowercased())")
        }
        .accessibilityElement(children: .contain)
    }

    private var longRestControls: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(isOn: Binding(
                get: { vm.pomodoroLongRestMinutes != nil },
                set: { on in
                    vm.pomodoroLongRestMinutes = on ? max(15, vm.pomodoroRestMinutes) : nil
                    vm.pomodoroLongRestEvery = on ? 2 : nil
                })) {
                Text("Long rest").font(FlintFont.text(15, weight: .semibold)).foregroundStyle(FlintBrand.bone)
            }
            .tint(FlintBrand.ember)
            if vm.pomodoroLongRestMinutes != nil {
                pomodoroRow("Long rest", value: vm.pomodoroLongRestMinutes ?? 15, unit: "min",
                            range: max(5, vm.pomodoroRestMinutes)...60, step: 5) {
                    vm.pomodoroLongRestMinutes = $0
                }
                pomodoroRow("Every", value: vm.pomodoroLongRestEvery ?? 2, unit: "rounds",
                            range: 2...max(2, vm.pomodoroRounds - 1), step: 1) {
                    vm.pomodoroLongRestEvery = $0
                }
            }
        }
    }

    private var pomodoroSummary: String {
        guard let plan = vm.pomodoroPlan else {
            return "These settings aren't valid yet: focus 5–120, rest 1–60, rounds 2–8."
        }
        return "\(plan.rounds) rounds of \(plan.focusMinutes) min with \(plan.restMinutes)-min rests — \(plan.totalMinutes) min in all."
    }

    private func minutesLabel(_ minutes: Int) -> String {
        minutes % 60 == 0 ? "\(minutes / 60)h" : "\(minutes)m"
    }

    // MARK: Apps (M04/M05)

    private var blockRow: some View {
        Button { showPicker = true } label: {
            VStack(alignment: .leading, spacing: 12) {
                HStack(spacing: 8) {
                    VStack(alignment: .leading, spacing: 2) {
                        HStack(spacing: 5) {
                            Text(groupTitle)
                                .font(FlintFont.text(15, weight: .semibold))
                                .foregroundStyle(FlintBrand.bone)
                            if let summary = countSummary {
                                Text("· \(summary)")
                                    .font(FlintFont.text(15, weight: .medium))
                                    .foregroundStyle(FlintBrand.mist)
                            }
                        }
                        Text("Tap to change apps or pick a group")
                            .flintSecondary(12)
                    }
                    .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 8)
                    FlintChevron()
                }
                if !vm.selection.flintPickedTargets.isEmpty {
                    FlintAppIconStack(targets: vm.selection.flintPickedTargets, size: 36, limit: 6)
                }
            }
            .padding(EdgeInsets(top: 14, leading: 16, bottom: 14, trailing: 16))
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 22, style: .continuous).fill(FlintBrand.card))
            .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(FlintBrand.bone(0.08)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(accessibilityAppsLabel)
        .accessibilityHint("Opens Apple’s picker")
    }

    private var groupTitle: String {
        vm.savedGroups.first { $0.selection == vm.selection }?.name ?? "What rests"
    }

    private var countSummary: String? {
        let counts = SelectionCounts(apps: vm.selection.applicationTokens.count,
                                     categories: vm.selection.categoryTokens.count,
                                     sites: vm.selection.webDomainTokens.count)
        return counts.total == 0 ? nil : counts.summary
    }

    private var accessibilityAppsLabel: String {
        let counts = SelectionCounts(apps: vm.selection.applicationTokens.count,
                                     categories: vm.selection.categoryTokens.count,
                                     sites: vm.selection.webDomainTokens.count)
        return counts.total == 0 ? "What rests, choose apps" : "\(groupTitle), \(counts.spokenSummary)"
    }

    private var groupChips: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(vm.savedGroups) { group in
                    Button { vm.applyGroup(group) } label: {
                        HStack(spacing: 8) {
                            FlintAppIconStack(targets: group.selection.flintPickedTargets, size: 22, border: FlintBrand.card, limit: 2)
                            Text(group.name)
                                .font(FlintFont.text(13, weight: .semibold))
                                .fixedSize()
                            Text("\(group.itemCount)")
                                .font(FlintFont.text(13, weight: .medium))
                                .foregroundStyle(FlintBrand.mist)
                        }
                        .fixedSize()
                    }
                    .buttonStyle(GroupChipButtonStyle(selected: group.selection == vm.selection))
                    .accessibilityLabel("\(group.name), \(group.itemCount) \(group.itemCount == 1 ? "item" : "items")")
                    .contextMenu {
                        Button(role: .destructive) { vm.deleteGroup(group.id) } label: {
                            Label("Delete group", systemImage: "trash")
                        }
                    }
                }
                if vm.selectionCount > 0 {
                    Button("Save as group") { showSaveGroup = true }
                        .font(FlintFont.text(13, weight: .semibold))
                        .foregroundStyle(FlintBrand.mist)
                        .padding(.horizontal, 14)
                        .frame(minHeight: 40)
                        .overlay(Capsule().strokeBorder(FlintBrand.bone(0.22),
                                                        style: StrokeStyle(lineWidth: 1, dash: [4, 3])))
                }
            }
            .padding(.horizontal, 4)
            .padding(.vertical, 2)
        }
        .scrollClipDisabledIfAvailable()
    }

    // MARK: Break level (M04)

    private var breakLevels: some View {
        VStack(spacing: 8) {
            ForEach(BreakLevel.allCases, id: \.self) { level in
                breakLevelRow(level)
            }
        }
    }

    private func breakLevelRow(_ level: BreakLevel) -> some View {
        let selected = vm.breakLevel == level
        return Button { vm.breakLevel = level } label: {
            HStack(spacing: 12) {
                ZStack {
                    Circle().strokeBorder(selected ? FlintBrand.ember : FlintBrand.bone(0.3), lineWidth: 1.5)
                        .frame(width: 20, height: 20)
                    Circle().fill(selected ? FlintBrand.ember : .clear).frame(width: 10, height: 10)
                }
                .accessibilityHidden(true)
                // At accessibility sizes a trailing badge leaves the hint a few characters per
                // line, so it moves under the copy.
                let large = typeSize.isAccessibilitySize
                VStack(alignment: .leading, spacing: large ? 6 : 2) {
                    Text(title(for: level))
                        .font(FlintFont.text(15, weight: .semibold))
                        .foregroundStyle(FlintBrand.bone)
                    Text(hint(for: level))
                        .font(FlintFont.text(12))
                        .foregroundStyle(FlintBrand.mist)
                        .fixedSize(horizontal: false, vertical: true)
                    if large { levelBadge(level, selected: selected) }
                }
                .multilineTextAlignment(.leading)
                Spacer(minLength: 8)
                if !large { levelBadge(level, selected: selected) }
            }
            .padding(EdgeInsets(top: 10, leading: 14, bottom: 10, trailing: 14))
            .frame(maxWidth: .infinity, minHeight: 62, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(selected ? FlintBrand.ember.opacity(0.10) : FlintBrand.card))
            .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous)
                .strokeBorder(selected ? FlintBrand.ember.opacity(0.6) : FlintBrand.bone(0.08)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }

    private func levelBadge(_ level: BreakLevel, selected: Bool) -> some View {
        Text(badge(for: level))
            .font(FlintFont.text(11, weight: .semibold, relativeTo: .caption2))
            .foregroundStyle(selected ? FlintBrand.emberText : FlintBrand.mist)
            .padding(.horizontal, 8)
            .frame(minHeight: 22)
            .background(Capsule().fill(selected ? FlintBrand.ember.opacity(0.18) : FlintBrand.bone(0.06)))
            .fixedSize()
    }

    private func title(for level: BreakLevel) -> String {
        switch level {
        case .easy: "Easy"
        case .harder: "Harder"
        case .hardcore: "Hardcore"
        }
    }

    private func hint(for level: BreakLevel) -> String {
        switch level {
        case .easy: "Stop any time."
        case .harder: "No early stop. Breaks wait a few minutes."
        case .hardcore: "No breaks, no stop, can’t delete Flint until it ends."
        }
    }

    private func badge(for level: BreakLevel) -> String {
        switch level {
        case .easy: "Flexible"
        case .harder: "Recommended"
        case .hardcore: "Free in Flint"
        }
    }

    private func note(for level: BreakLevel) -> String {
        switch level {
        case .easy: "You can end this session whenever you like."
        case .harder: "You can still stop early, after a wait: 1 minute the first time, then 5, then 15. Going back from the wait doesn't count."
        case .hardcore: "Can’t be stopped early in Flint — it ends at its set time. The Emergency Pass (one every 7 days after you use it) is there if you really need out. While it runs, iOS also won't let apps be deleted. Screen Time access can always be turned off in iOS Settings."
        }
    }
}

/// The group chip from M05: two overlapping real icons, the name and its count.
struct GroupChipButtonStyle: ButtonStyle {
    var selected = false
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(FlintBrand.bone)
            .padding(.leading, 8)
            .padding(.trailing, 14)
            .frame(minHeight: 40)
            .background(Capsule().fill(selected ? FlintBrand.ember.opacity(0.14) : FlintBrand.bone(0.05)))
            .overlay(Capsule().strokeBorder(selected ? FlintBrand.ember.opacity(0.6) : FlintBrand.bone(0.12)))
            .contentShape(Capsule())
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

private extension View {
    /// Let a horizontal chip strip bleed to the sheet edge without clipping its shadow/ring.
    @ViewBuilder func scrollClipDisabledIfAvailable() -> some View {
        if #available(iOS 17.0, *) {
            scrollClipDisabled()
        } else {
            self
        }
    }
}
