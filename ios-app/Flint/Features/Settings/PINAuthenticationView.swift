import SwiftUI
import FlintCore

/// Current-PIN authentication intentionally accepts legacy values of any length. New-PIN
/// validation belongs to the setup form, not this challenge or the app-open gate.
struct PINAuthenticationView: View {
    let title: String
    let authenticate: (String) -> Bool
    let onSuccess: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var pin = ""
    @State private var showError = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 20) {
                Text("Enter your current PIN").flintSecondary(16)
                SecureField("Current PIN", text: $pin)
                    .keyboardType(.numberPad)
                    .textContentType(.password)
                    .multilineTextAlignment(.center)
                    .font(FlintFont.display(28))
                    .foregroundStyle(FlintBrand.bone)
                    .accessibilityIdentifier("current-pin")
                if showError {
                    Text("Incorrect PIN").foregroundStyle(FlintBrand.danger)
                }
                Button("Continue") {
                    let accepted = authenticate(pin)
                    pin = ""
                    if accepted {
                        onSuccess()
                        dismiss()
                    } else {
                        showError = true
                    }
                }
                .buttonStyle(EmberButtonStyle())
                Spacer()
            }
            .padding(24)
            .background(FlintGroundBackground())
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}
