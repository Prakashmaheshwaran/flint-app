import Foundation

public enum FlintProtectionTrigger: String, Codable, CaseIterable {
    case launch, foreground, significantTimeChange, timeZoneChange, configEdit

    var reappliesOpenLimitShields: Bool { self == .launch }
}

@MainActor
public protocol FlintProtectionLayer {
    var domain: String { get }
    func reconcile(trigger: FlintProtectionTrigger) async throws -> FlintArmingHealth.DomainReport
}

@MainActor
struct FlintOpenLimitProtectionLayer: FlintProtectionLayer {
    let domain = "openLimits"
    let reload: (Bool) throws -> FlintArmingHealth.DomainReport

    func reconcile(trigger: FlintProtectionTrigger) async throws -> FlintArmingHealth.DomainReport {
        try reload(trigger.reappliesOpenLimitShields)
    }
}

/// One lifecycle entry point for every layer. Task chaining also serializes across suspension
/// points: a foreground request during launch waits for every launch layer to finish.
@MainActor
public final class FlintProtectionReconciler {
    private let layers: [any FlintProtectionLayer]
    private let persist: (FlintProtectionObservation) -> Void
    private let publish: () -> Void
    private let now: () -> Date
    private let prepare: () -> Void
    private var tail: Task<FlintProtectionObservation, Never>?

    /// `publish` re-publishes the shared status projection once every layer has reconciled.
    public init(layers: [any FlintProtectionLayer], now: @escaping () -> Date = Date.init,
                persist: @escaping (FlintProtectionObservation) -> Void = { $0.save() },
                prepare: @escaping () -> Void = {},
                publish: @escaping () -> Void = {}) {
        self.layers = layers
        self.now = now
        self.persist = persist
        self.publish = publish
        self.prepare = prepare
    }

    @discardableResult
    public func reconcile(trigger: FlintProtectionTrigger) async -> FlintProtectionObservation {
        let previous = tail
        let next = Task { @MainActor in
            _ = await previous?.value
            prepare()
            var domains: [String: FlintArmingHealth.DomainReport] = [:]
            for layer in layers {
                do {
                    domains[layer.domain] = try await layer.reconcile(trigger: trigger)
                } catch {
                    domains[layer.domain] = .init(enabled: 0, attempted: 0, armed: 0,
                        failures: [.init(activityName: layer.domain, reason: String(describing: error))])
                }
            }
            let observation = FlintProtectionObservation(observedAt: now(), trigger: trigger, domains: domains)
            persist(observation)
            publish()
            NotificationCenter.default.post(name: .flintProtectionReconciled, object: nil)
            return observation
        }
        tail = next
        return await next.value
    }
}
