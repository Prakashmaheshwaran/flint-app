package com.flint.peakfocus.feature.settings

/** Accessibility fixes must leave this module through the app's prominent disclosure gate. */
internal fun routePermissionFix(
    kind: PermissionKind,
    discloseAccessibility: () -> Unit,
    openOtherSettings: (PermissionKind) -> Unit,
) {
    if (kind == PermissionKind.ACCESSIBILITY) discloseAccessibility() else openOtherSettings(kind)
}
