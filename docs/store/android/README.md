# Android Play submission package (draft)

Prepared 2026-10-28 for `com.flint.peakfocus`. These files are editable owner
drafts, not Console submissions or approval evidence. No Google account action,
upload, signing enrollment, release, or publication has been performed.

- [Target API assessment](target-api.md)
- [Upload signing and key custody](signing.md)
- [APK and app bundle verification](bundles.md)
- [Listing copy and asset checklist](listing.md)
- [On-device inventory and Data safety answers](data-safety.md)
- [Accessibility declaration and demo shot list](accessibility.md)
- [Permissions and foreground service declarations](permissions.md)
- [Public privacy policy](../../privacy-policy.md)

## Release gates

The declarations describe the intended local-only release **without Settings,
uninstall or disable interception**. The coordinator assigned removal of that
consumer guard to lane C under the protection contract. Do not submit these
answers or publish the policy against a build that still reads Settings text or
shields those system controls. Truthful disclosure alone does not resolve
[Play's prohibition](https://support.google.com/googleplay/android-developer/answer/16558241).

Before submission, the owner must:

1. Confirm lane C removal and the matching E4 disclosure copy are merged; inspect
   the exact release artifact and demonstrate freely reachable disable/uninstall
   controls. Reconcile all drafts if the final code differs.
2. Obtain green exact-head Android CI and script selftests for packaging changes;
   complete lane C safety/enforcement and lane F real-history acceptance. Build
   and unit tests do not prove physical-device/OEM behavior.
3. Generate and securely back up the owner upload key, configure signing, enroll
   in Play App Signing, and verify the bundle signer and update-install path.
   Development-signed previews are not store submissions.
4. Reconcile the final data inventory, SDK/dependency list and merged permissions
   against the AAB; confirm no network/account/debug sandbox code. Publish the
   privacy policy at a public static HTTPS URL under owner control and provide
   that URL and the real developer/support contact in Console. No URL is live
   merely because the Markdown exists.
5. Capture real release-build screenshots and declaration videos. Complete
   Accessibility and FGS declarations, Data safety, app access, ads, target
   audience and content-rating forms with the exact current Console questions.
   No accounts/login are needed; grant special access manually on a test device.
6. Complete any account/device verification and testing requirements shown in the
   owner's Console, then explicitly authorize upload and review submission.
   Acceptance and production rollout remain Google's and the owner's decisions.

All policy links in this package were accessed **2026-10-28**. Refresh them at
submission; the drafts do not assert eligibility or predict review outcomes.
