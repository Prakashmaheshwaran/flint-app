# v1 store package and owner submission checklist

These are **draft packages**, not submitted listings, approved declarations or published
apps. The [capability ledger](../acceptance/v1-capability-ledger.md) is the implementation
evidence index; the [single physical-device plan](../acceptance/device-acceptance-plan.md)
records acceptance. No physical-device or store approval is claimed.

## Package map

- **iOS:** [metadata/privacy/assets](ios/store-package.md), [review notes](ios/review-notes.md),
  [private signing/archive runbook](ios/signing-and-release.md),
  [privacy API inventory](ios/privacy-api-inventory.md), [unsigned Release CI](ios/release-ci.md).
- **Android:** [package index](android/README.md), [listing](android/listing.md),
  [Data safety inventory](android/data-safety.md), [Accessibility declaration/video](android/accessibility.md),
  [permissions/FGS](android/permissions.md), [signing](android/signing.md),
  [APK/AAB](android/bundles.md), [shrinking/mappings](android/shrinking.md).
- **Shared:** [canonical privacy-policy draft](../privacy-policy.md). The Android build
  packages this policy offline; committing it does not publish an HTTPS page.

All package claims must match the **final selected artifact**, its merged source and
the actual device results. Update drafts before submission if protection, storage,
permissions, dependencies, notification channels or OS surfaces change. Do not promote
planned ADR behavior or placeholder screenshots into public claims.

## One human-owned submission checklist

Each line needs an owner/date/evidence link in the release record. Account access,
credentials, signing, payments, uploads, review and publication require the owner's
separate authorization; completing the repository work does not perform these actions.

- [ ] Select exact integration commit, marketing version/build numbers and supported device/OS
  scope; record final CI/artifact hashes. Resolve or explicitly exclude all required failed,
  blocked and unrun acceptance cases. Do not label emulator/fixture data as hardware proof.
- [ ] Complete [physical-device acceptance](../acceptance/device-acceptance-plan.md), beginning
  with emergency/recovery safety, Hardcore rights and global pass/bounded access. Record
  unsupported browsers/OEMs, clock uncertainty and observed OS delivery latency.
- [ ] **Apple account/signing:** verify intended team, membership, five Family Controls
  distribution approvals/profiles (host, monitor, shield-config, shield-action, report),
  shared App Group and the added WidgetKit bundle/provisioning. Account status is unknown
  until inspected; request only missing reviewed approvals.
- [ ] **Apple artifact:** configure ignored private signing files, increment coordinated build,
  archive/export the signed Release build and inspect every embedded signature, entitlement,
  six privacy manifests, icon/resource and DEBUG-fixture exclusion. Validate archive privacy
  requirements with current Apple tooling; unsigned simulator CI is insufficient.
- [ ] **Google key/account:** create/custody/back up the intended upload key privately, verify
  public certificate, Play App Signing enrollment/ownership, developer account verification
  and required testing eligibility. Never commit keys/passwords or reuse a preview key for
  a store claim.
- [ ] **Google artifact/update:** build intended owner-signed release AAB/APK, retain provenance,
  exclusions and R8 mapping beyond CI expiry; validate a Play-delivered in-place update with
  existing local data. Different certificates cannot update one another; uninstall may erase it.
- [ ] Publish stable HTTPS privacy/support destinations with real developer contact. Confirm
  the published policy matches bundled copy and final local history/permission/widget data.
  No placeholder or uncreated URL is a submission-ready destination.
- [ ] Finalize metadata, age/target-audience/content answers, App Privacy/Data safety,
  Accessibility and specialUse FGS declarations against final code/artifact and current
  Console forms. Include transient foreground/URL processing, local persistence/retention,
  user-initiated external links and OS/launcher/notification/Live Activity visibility.
  Local-only processing alone is not evidence that a form has been completed/approved.
- [ ] Capture genuine final-device screenshots and permission/Accessibility/FGS review videos.
  No DEBUG fixtures, synthetic Screen Time, seeded history or unimplemented feature claims.
  Explain no accounts/analytics, optional notifications, supported browser coverage and OS exits.
- [ ] Explicitly authorize the exact archive/AAB upload and intended internal distribution.
  Read back processing status; install the actual TestFlight/Play-delivered build and repeat
  the critical smoke/update checks. A successful upload is not review acceptance.
- [ ] Separately authorize review submission and eventual rollout/publication, naming destination,
  build and scope; read back final state. Retain decisions, approved package text, artifact
  hashes, device evidence and rollback/support ownership without secrets.

## Reconciliation boundary

Current integration has local history, optional focus-end reminders and read-only glance
surfaces. Protection producers can be unavailable; a widget or Activity does not create
enforcement authority. Android's mandatory `flint_enforcement` FGS, optional
`flint_ongoing_session` Path A status and `focus_sessions` end-reminder channels are
distinct. iOS ActivityKit is opt-in; WidgetKit adds a sixth privacy/provisioning bundle.
Android includes offline policy/licenses; the iOS privacy note/store drafts do not establish
equivalent bundled legal screens. Use the ledger and platform inventories for exact gaps.
