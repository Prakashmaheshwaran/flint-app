import Foundation
import FamilyControls

/// Wraps AuthorizationCenter for the individual (adult self-blocker) flow.
/// Re-check on foreground entry to pick up changes made in Settings. Becoming active alone
/// must not cancel a request: Apple's permission sheet also causes scene phase changes.
@MainActor
final class AuthorizationModel: ObservableObject {
    @Published private(set) var status: AuthorizationStatus = .notDetermined
    @Published private(set) var lastError: String?
    @Published private(set) var isRequesting = false

    private let readStatus: () -> AuthorizationStatus
    private let request: () async throws -> Void
    private let waitForTimeout: () async throws -> Void
    private var pending: PendingRequest?

    private struct PendingRequest {
        let id: UUID
        let continuation: CheckedContinuation<Void, Never>
        var provider: Task<Void, Never>?
        var timeout: Task<Void, Never>?
    }

    private enum Outcome {
        case completed, cancelled, timedOut
        case failed(String)
    }

    convenience init() {
        #if DEBUG
        if let preview = AuthorizationPreview(arguments: ProcessInfo.processInfo.arguments) {
            self.init(readStatus: { preview.status }, request: { try await preview.request() },
                      waitForTimeout: { try await preview.waitForTimeout() })
            return
        }
        #endif
        self.init(readStatus: { AuthorizationCenter.shared.authorizationStatus }, request: {
            try await AuthorizationCenter.shared.requestAuthorization(for: .individual)
        })
    }

    init(
        readStatus: @escaping () -> AuthorizationStatus,
        request: @escaping () async throws -> Void,
        waitForTimeout: @escaping () async throws -> Void = {
            try await Task.sleep(nanoseconds: 120_000_000_000)
        }
    ) {
        self.readStatus = readStatus
        self.request = request
        self.waitForTimeout = waitForTimeout
    }

    func refresh() {
        status = readStatus()
        if status == .approved || status == .denied {
            lastError = nil
            if let pending { finish(pending.id, with: .completed) }
        }
    }

    func requestAuthorization() async {
        refresh()
        // A denied request has a Settings recovery path. Do not promise another system prompt
        // from any caller (Today, onboarding, or Insights), or re-request an existing approval.
        guard status == .notDetermined, !isRequesting, !Task.isCancelled else { return }
        let id = UUID()
        await withTaskCancellationHandler {
            await withCheckedContinuation { continuation in
                guard !Task.isCancelled else { continuation.resume(); return }
                isRequesting = true
                lastError = nil
                pending = PendingRequest(id: id, continuation: continuation)
                // Unstructured tasks let the waiter finish even when the provider ignores
                // cancellation. A task group would wait for that child on scope exit.
                pending?.provider = Task { [weak self, request] in
                    let outcome: Outcome
                    do {
                        try await request()
                        outcome = .completed
                    } catch is CancellationError {
                        outcome = .cancelled
                    } catch FamilyControlsError.authorizationCanceled {
                        outcome = .cancelled
                    } catch {
                        outcome = .failed(error.localizedDescription)
                    }
                    self?.finish(id, with: outcome)
                }
                pending?.timeout = Task { [weak self, waitForTimeout] in
                    do { try await waitForTimeout() } catch { return }
                    guard !Task.isCancelled else { return }
                    self?.finish(id, with: .timedOut)
                }
            }
        } onCancel: {
            Task { @MainActor [weak self] in self?.finish(id, with: .cancelled) }
        }
    }

    /// Stops waiting in Flint; cancellation cannot promise to dismiss Apple's permission sheet.
    func cancelRequest() {
        if let pending { finish(pending.id, with: .cancelled) }
    }

    private func finish(_ id: UUID, with outcome: Outcome) {
        guard let current = pending, current.id == id else { return }
        // Invalidate before resuming the caller or cancelling either task. Late completions and
        // cancellation handlers from an earlier attempt cannot update a newer attempt's state.
        pending = nil
        current.provider?.cancel()
        current.timeout?.cancel()
        status = readStatus()
        lastError = nil
        if status == .notDetermined {
            switch outcome {
            case .completed:
                lastError = "Screen Time access wasn’t enabled. You can try again."
            case .failed(let message):
                lastError = message
            case .timedOut:
                lastError = "Screen Time is taking longer than expected. If a permission sheet is still open, finish or dismiss it before trying again."
            case .cancelled:
                break
            }
        }
        isRequesting = false
        current.continuation.resume()
    }
}
