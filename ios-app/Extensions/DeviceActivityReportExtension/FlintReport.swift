import DeviceActivity
import SwiftUI
#if canImport(ExtensionKit)
import ExtensionKit
#endif

/// The DeviceActivityReport extension — the ONLY supported source of per-app/website usage data
/// and localized app names. The host app embeds a `DeviceActivityReport` view that renders this
/// scene; the underlying data never crosses into the host process.
@main
struct FlintReport: DeviceActivityReportExtension {
    var body: some DeviceActivityReportScene {
        TotalActivityReport { summary in
            TotalActivityView(summary: summary)
        }
    }
}
