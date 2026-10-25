import SwiftUI
import UIKit
import FlintCore

/// UIKit-backed chrome (navigation bars, system switches, segmented pickers) styled once, so
/// pushed settings screens match the Ember & Mist screens around them.
enum FlintAppearance {
    static func apply() {
        let bone = FlintBrand.boneUI
        let nav = UINavigationBarAppearance()
        nav.configureWithTransparentBackground()
        nav.backgroundColor = UIColor(FlintBrand.ground).withAlphaComponent(0.92)
        nav.shadowColor = .clear
        if let display = UIFont(name: "ShipporiMincho-Regular", size: 30) {
            nav.largeTitleTextAttributes = [.font: display, .foregroundColor: bone]
        }
        if let title = UIFont(name: "HankenGrotesk-Medium", size: 16) {
            nav.titleTextAttributes = [.font: title, .foregroundColor: bone]
        }
        if let button = UIFont(name: "HankenGrotesk-Regular", size: 16) {
            let item = UIBarButtonItemAppearance()
            item.normal.titleTextAttributes = [.font: button, .foregroundColor: bone]
            nav.buttonAppearance = item
            nav.doneButtonAppearance = item
        }
        UINavigationBar.appearance().standardAppearance = nav
        UINavigationBar.appearance().scrollEdgeAppearance = nav
        UINavigationBar.appearance().compactAppearance = nav
        UINavigationBar.appearance().tintColor = bone

        UISwitch.appearance().onTintColor = FlintBrand.emberUI

        let segmented = UISegmentedControl.appearance()
        segmented.selectedSegmentTintColor = UIColor(FlintBrand.segment)
        segmented.backgroundColor = UIColor(FlintBrand.bone(0.05))
        if let font = UIFont(name: "HankenGrotesk-Medium", size: 14) {
            segmented.setTitleTextAttributes([.font: font, .foregroundColor: bone], for: .selected)
            segmented.setTitleTextAttributes([.font: font, .foregroundColor: FlintBrand.mistUI], for: .normal)
        }
    }
}

extension View {
    /// Night-palette treatment for `Form`/`List` screens: ground behind, quiet card rows,
    /// Hanken Grotesk body, ember controls.
    func flintForm() -> some View {
        self
            .scrollContentBackground(.hidden)
            .background(FlintGroundBackground())
            .font(FlintFont.text(15))
            .foregroundStyle(FlintBrand.bone)
            .tint(FlintBrand.ember)
            .environment(\.defaultMinListRowHeight, 52)
            .flintTabBarInset()
    }

    /// Section header in the design's eyebrow voice.
    func flintSectionHeader() -> some View {
        flintEyebrow().padding(.leading, -4)
    }
}
