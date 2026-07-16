import SwiftUI
import FlintCore

struct SettingsView: View {
    @State private var pinIsSet = FlintPIN.isSet(FlintGroupStore())
    @State private var showSetPIN = false
    @State private var newPIN = ""
    @State private var armingHealth = FlintGroupStore()?.loadArmingHealth() ?? FlintArmingHealth()

    var body: some View {
        NavigationStack {
            Form {
                Section("App lock") {
                    if pinIsSet {
                        Label("App PIN is on", systemImage: "lock.fill")
                            .foregroundStyle(.green)
                        Button("Change PIN") { newPIN = ""; showSetPIN = true }
                        Button("Remove PIN", role: .destructive) {
                            FlintPIN.clear(FlintGroupStore()); pinIsSet = false
                        }
                    } else {
                        Button("Set app PIN") { newPIN = ""; showSetPIN = true }
                    }
                }

                Section {
                    NavigationLink {
                        SleepModeView()
                    } label: {
                        Label("Sleep Mode & Morning Assist", systemImage: "bed.double.fill")
                    }
                } header: {
                    Text("Sleep")
                } footer: {
                    Text("Shield everything except your allowed apps from bedtime to wake-up — "
                         + "and through a morning wind-up. Its windows run on the Schedules engine.")
                }

                Section {
                    NavigationLink {
                        WebRestrictionsView()
                    } label: {
                        Label("Safari restrictions & Private Browsing", systemImage: "safari")
                    }
                } header: {
                    Text("Web & Safari")
                } footer: {
                    Text("Block sites and lock down Safari Private Browsing while sessions run.")
                }

                Section {
                    NavigationLink {
                        FocusFilterView()
                    } label: {
                        Label("Focus Filter", systemImage: "moon.circle")
                    }
                } header: {
                    Text("Automation")
                } footer: {
                    Text("Attach Flint to a system Focus (Work, Sleep, …) so blocking starts and "
                         + "stops with it.")
                }

                if armingHealth.attemptedTotal > 0 || !armingHealth.isHealthy {
                    Section {
                        if !armingHealth.isHealthy {
                            Label("\(armingHealth.failures.count) \(armingHealth.failures.count == 1 ? "rule" : "rules") failed to arm",
                                  systemImage: "exclamationmark.triangle.fill")
                                .foregroundStyle(.red)
                            ForEach(armingHealth.failures, id: \.activityName) { failure in
                                Text("\(failure.activityName) — \(failure.reason)")
                                    .font(.caption2.monospaced())
                                    .foregroundStyle(.secondary)
                            }
                        } else {
                            Label("All \(armingHealth.attemptedTotal) \(armingHealth.attemptedTotal == 1 ? "rule" : "rules") armed with iOS",
                                  systemImage: "checkmark.shield.fill")
                                .foregroundStyle(.green)
                        }
                        if armingHealth.isNearCap {
                            Text("Flint last attempted \(armingHealth.attemptedTotal) background "
                                 + "registrations. iOS does not publish a fixed limit; refusals "
                                 + "have been observed around "
                                 + "\(FlintArmingHealth.observedActivityThreshold). Consider "
                                 + "merging schedules or removing unused rules before adding more.")
                                .font(.footnote)
                                .foregroundStyle(.orange)
                        }
                    } header: {
                        Text("Blocking health")
                    } footer: {
                        Text("Whether iOS accepted every schedule, Time Limit, and Open Limit at "
                             + "the last re-arm. iOS quietly caps concurrent registrations per app; "
                             + "Flint surfaces a refused registration instead of letting the rule "
                             + "die silently.")
                    }
                }

                Section("Stronger protection") {
                    Text("For true anti-uninstall protection, set a **system Screen Time passcode** "
                         + "(Settings → Screen Time). It prevents deleting Flint or turning blocking "
                         + "off. Flint can't enforce that itself — it's an Apple limitation.")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }

                Section {
                    Text("Free · open source · no accounts · no telemetry")
                        .font(.footnote)
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Settings")
            .onAppear { armingHealth = FlintGroupStore()?.loadArmingHealth() ?? FlintArmingHealth() }
            .alert("Set a 4–6 digit PIN", isPresented: $showSetPIN) {
                TextField("PIN", text: $newPIN)
                Button("Save") {
                    if newPIN.count >= 4 { FlintPIN.set(newPIN, FlintGroupStore()); pinIsSet = true }
                    newPIN = ""
                }
                Button("Cancel", role: .cancel) { newPIN = "" }
            }
        }
    }
}
