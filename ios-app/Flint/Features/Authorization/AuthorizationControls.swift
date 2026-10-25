import SwiftUI
import UIKit
import FlintCore

/// Shared recovery actions keep every permission entry point consistent with the model guard.
struct AuthorizationControls: View {
    @ObservedObject var auth: AuthorizationModel
    let identifierPrefix: String
    var onApproved: () -> Void = {}
    @Environment(\.openURL) private var openURL
    @State private var settingsUnavailable = false

    var body: some View {
        VStack(spacing: 12) {
            if auth.status == .denied {
                Text("Screen Time access is off. Open Settings to view Flint’s settings, or go to Settings → Screen Time → Apps with Screen Time Access. Enable Flint if it’s listed, then return here.")
                    .flintSecondary(14)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityIdentifier("\(identifierPrefix).guidance")
                Button {
                    guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
                    openURL(url) { settingsUnavailable = !$0 }
                } label: { actionLabel("Open Settings") }
                .buttonStyle(EmberButtonStyle())
                .accessibilityIdentifier("\(identifierPrefix).action")
                Button("Check access again") {
                    auth.refresh()
                    if auth.status == .approved { onApproved() }
                }
                .font(FlintFont.text(14, weight: .medium))
                .foregroundStyle(FlintBrand.bone)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .frame(minHeight: 44)
                .accessibilityIdentifier("\(identifierPrefix).check")
                if settingsUnavailable {
                    feedback("Settings couldn’t be opened. Open the Settings app manually, then check Flint’s Screen Time access.")
                }
            } else {
                if let error = auth.lastError { feedback(error) }
                Button {
                    Task {
                        await auth.requestAuthorization()
                        if auth.status == .approved { onApproved() }
                    }
                } label: {
                    actionLabel(auth.isRequesting ? "Requesting access…" : auth.lastError == nil ? "Allow Screen Time" : "Retry Screen Time")
                }
                .buttonStyle(EmberButtonStyle())
                .disabled(auth.isRequesting)
                .accessibilityIdentifier("\(identifierPrefix).action")
                if auth.isRequesting {
                    Button("Stop waiting") { auth.cancelRequest() }
                        .font(FlintFont.text(14, weight: .medium))
                        .foregroundStyle(FlintBrand.bone)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(minHeight: 44)
                        .accessibilityIdentifier("\(identifierPrefix).cancel")
                }
            }
        }
    }

    private func actionLabel(_ title: String) -> some View {
        Text(title)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.horizontal, 16)
            .padding(.vertical, 12)
    }

    private func feedback(_ message: String) -> some View {
        Text(message)
            .font(FlintFont.text(13))
            .foregroundStyle(FlintBrand.danger)
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity)
            .accessibilityIdentifier("\(identifierPrefix).error")
    }
}
