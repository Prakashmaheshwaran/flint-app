# Android Play submission package (draft)

Prepared 2026-10-28 for `com.flint.peakfocus`. These files are editable owner
drafts, not Console submissions or approval evidence. No Google account action,
upload, signing enrollment, release, or publication has been performed.

- [Target API assessment](target-api.md)
- [Upload signing and key custody](signing.md)
- [APK and app bundle verification](bundles.md)
- [Shrinking, reflective WorkManager boundary and mapping evidence](shrinking.md)
- [Listing copy and asset checklist](listing.md)
- [On-device inventory and Data safety answers](data-safety.md)
- [Accessibility declaration and demo shot list](accessibility.md)
- [Permissions and foreground service declarations](permissions.md)
- [Public privacy policy](../../privacy-policy.md)

Android You includes the canonical policy and generated release-dependency/font notices
as native offline screens; they need no WebView or network access. Regenerate/inspect
the final artifact's legal inventory after dependency changes; historical library counts
are not a current release count.

## Release gates

The declarations describe the local-only release **without Settings,
uninstall or disable interception**. Lane C removed that consumer guard and its
Settings text sweep in [PR105](https://github.com/Prakashmaheshwaran/flint-app/pull/105)
(integration commit `a34cbba754ae33211e9bd18ddb29ad8310157355`); E4 reconciles the
matching disclosure and shared enable gate. Do not submit these
answers or publish the policy against a build that still reads Settings text or
shields those system controls. Truthful disclosure alone does not resolve
[Play's prohibition](https://support.google.com/googleplay/android-developer/answer/16558241).

The [single owner submission checklist](../README.md#one-human-owned-submission-checklist)
covers account/signing, final artifact/data inventory, hardware acceptance, public URLs,
assets/declarations, upload/review and publication. The platform files above provide the
technical inputs; none of their unchecked tasks is a completion claim.

Merged F history, K glance/notification code and C139 editor state are inventoried in
[data-safety.md](data-safety.md). Use the [capability ledger](../../acceptance/v1-capability-ledger.md)
for current live integration boundaries and the [shared device plan](../../acceptance/device-acceptance-plan.md)
for actual results. This package remains blocked on final artifact and physical/OEM proof,
owner signing/Play delivery and truthful declaration review.

All policy links in this package were accessed **2026-10-28**. Refresh them at
submission; the drafts do not assert eligibility or predict review outcomes.
