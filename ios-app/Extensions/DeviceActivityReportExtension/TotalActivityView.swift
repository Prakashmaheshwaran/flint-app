import SwiftUI
import FlintCore

struct TotalActivityView: View {
    let totalActivity: String

    var body: some View {
        VStack(spacing: 8) {
            Text("Screen time today")
                .font(.headline)
                .foregroundStyle(.secondary)
            Text(totalActivity)
                .font(.system(size: 44, weight: .semibold))
                .foregroundStyle(FlintBrand.spark)
        }
        .padding()
    }
}

#Preview {
    TotalActivityView(totalActivity: "2h 14m")
}
