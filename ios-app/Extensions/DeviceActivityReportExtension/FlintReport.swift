import DeviceActivity
import SwiftUI

/// The DeviceActivityReport extension — the ONLY supported source of per-app/website usage data
/// and localized app names. The host app embeds a `DeviceActivityReport` view that renders this
/// scene; the underlying data never crosses into the host process.
@main
struct FlintReport: DeviceActivityReportExtension {
    var body: some DeviceActivityReportScene {
        TotalActivityReport { totalActivity in
            TotalActivityView(totalActivity: totalActivity)
        }
    }
}
