import SwiftUI
import FlintCore

/// New-PIN entry only. The presenter owns authentication before replacing an existing PIN.
struct PINSetupView: View {
    @ObservedObject var appLock: FlintAppLock
    let onSave: (String) -> Bool

    @Environment(\.dismiss) private var dismiss
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var typeSize
    @State private var pin = ""
    @State private var confirmation = ""
    @State private var saveFailed = false
    @FocusState private var focus: Field?

    private enum Field: Hashable { case pin, confirmation }

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
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(alignment: .leading, spacing: 20) {
                        // At accessibility sizes the fields come first so both stay above the
                        // number pad; the instruction follows them.
                        if !typeSize.isAccessibilitySize { instruction }

                        secureField("New PIN", text: $pin, identifier: "pin.setup.new", field: .pin)
                        secureField("Confirm PIN", text: $confirmation, identifier: "pin.setup.confirm", field: .confirmation)

                        if typeSize.isAccessibilitySize { instruction }

                        if let message = validationMessage {
                            Text(message)
                                .font(FlintFont.text(14))
                                .foregroundStyle(FlintBrand.danger)
                                .fixedSize(horizontal: false, vertical: true)
                                .accessibilityIdentifier("pin.setup.validation")
                        }
                        if saveFailed {
                            Text("Couldn’t save your PIN. Please try again.")
                                .font(FlintFont.text(14))
                                .foregroundStyle(FlintBrand.danger)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .padding(24)
                }
                // The number pad has no return key, and at large text sizes the confirmation
                // field sits below it: keep the focused field scrolled into view.
                .onChange(of: focus) { field in
                    guard let field else { return }
                    withAnimation(reduceMotion ? nil : .easeOut(duration: 0.2)) {
                        proxy.scrollTo(field, anchor: .center)
                    }
                }
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
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    if focus == .pin {
                        Button("Next") { focus = .confirmation }
                            .accessibilityIdentifier("pin.setup.next")
                    } else {
                        Button("Done") { focus = nil }
                    }
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

    private var instruction: some View {
        Text("Choose 4–6 digits (0–9), then enter them again to confirm.")
            .flintSecondary(15)
            .fixedSize(horizontal: false, vertical: true)
    }

    private func secureField(_ title: LocalizedStringKey, text: Binding<String>, identifier: String, field: Field) -> some View {
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
                .focused($focus, equals: field)
                .accessibilityIdentifier(identifier)
                .onSubmit(save)
        }
        .id(field)
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
