import SwiftUI
import FlintCore

/// New-PIN entry only. The presenter owns authentication before replacing an existing PIN.
struct PINSetupView: View {
    @ObservedObject var appLock: FlintAppLock
    let onSave: (String) -> Bool

    @Environment(\.dismiss) private var dismiss
    @State private var pin = ""
    @State private var confirmation = ""
    @State private var saveFailed = false

    private var canSave: Bool {
        // Use FlintAppLock's live edit precondition even before an interrupted sheet dismisses.
        appLock.state.phase == .active && !appLock.isLocked
            && FlintPIN.isValidNewPIN(pin) && pin == confirmation
    }

    private var validationMessage: String? {
        if !pin.isEmpty && !FlintPIN.isValidNewPIN(pin) {
            return "Use 4–6 digits (0–9), with no spaces."
        }
        if !confirmation.isEmpty && pin != confirmation {
            return "PINs don’t match. Enter the same PIN twice."
        }
        return nil
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("Choose 4–6 digits (0–9), then enter them again to confirm.")
                        .flintSecondary(15)

                    secureField("New PIN", text: $pin, identifier: "pin.setup.new")
                    secureField("Confirm PIN", text: $confirmation, identifier: "pin.setup.confirm")

                    if let message = validationMessage {
                        Text(message)
                            .font(FlintFont.text(14))
                            .foregroundStyle(FlintBrand.danger)
                            .accessibilityIdentifier("pin.setup.validation")
                    }
                    if saveFailed {
                        Text("Couldn’t save your PIN. Please try again.")
                            .font(FlintFont.text(14))
                            .foregroundStyle(FlintBrand.danger)
                    }
                }
                .padding(24)
            }
            .background(FlintGroundBackground())
            .navigationTitle("Set PIN")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", role: .cancel) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                        .disabled(!canSave)
                        .accessibilityIdentifier("pin.setup.save")
                }
            }
            .onChange(of: pin) { _ in saveFailed = false }
            .onChange(of: confirmation) { _ in saveFailed = false }
            .onDisappear {
                pin = ""
                confirmation = ""
            }
        }
    }

    private func secureField(_ title: String, text: Binding<String>, identifier: String) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(title).font(FlintFont.text(14)).foregroundStyle(FlintBrand.mist)
            SecureField(title, text: text)
                .keyboardType(.numberPad)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .font(FlintFont.text(22))
                .foregroundStyle(FlintBrand.bone)
                .padding(14)
                .background(RoundedRectangle(cornerRadius: 12).fill(FlintBrand.bone(0.05)))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(FlintBrand.bone(0.16)))
                .accessibilityIdentifier(identifier)
                .onSubmit(save)
        }
    }

    private func save() {
        guard canSave else { return }
        guard onSave(pin) else {
            saveFailed = true
            return
        }
        pin = ""
        confirmation = ""
        dismiss()
    }
}
