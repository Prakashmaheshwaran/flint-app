# Flint iOS App Store package — draft

Prepared 2026-10-28 for lane D6. Source baseline:
`fa190e9df96f61c13ea651ee39cae6f68057f0c6` on `integration/v1`.
This is preparation for human review, not a submitted listing or proof of release
readiness. No Apple account, signing, upload, TestFlight, or publication action was
performed. Companion: [review notes and physical-device script](review-notes.md).

## Release claim gate

Every customer-facing field below is **proposed copy, pending validation against
the final signed Release build**. Lane B owns iOS enforcement acceptance; lane C
owns Android acceptance before any cross-platform parity claim; lane F owns real
local history and Insights events. Their implementation/CI evidence and signed
physical-device results must be linked before the owner pastes this package into
App Store Connect. ADR-011 becomes the policy authority when merged; recheck
levels, breaks, overlaps, Open Limits, and Emergency Pass against that contract.

Do not add claims of unbreakable blocking, tamper-proof protection, guaranteed
background expiry, blocking in every browser, proven sleep/health benefits,
time saved, streaks, complete history, or iOS/Android parity. A countdown, source
comment, unit test, simulator fixture, or design mockup proves none of these.
At this baseline, timed monitor registration is best-effort in
[FlintSessionController.startBlockNow](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintSessionController.swift#L82-L94),
and completion is in memory in
[SessionViewModel.tick](../../../ios-app/Flint/Features/Sessions/SessionViewModel.swift#L156-L169).
These observations must be refreshed after B/F land; they are not claims about
the future release candidate.

## Proposed en-US metadata

Only the contents of the following four plain-text blocks are listing fields.
The name remains subject to availability and the owner's App Store record.
Proposed primary category: Productivity. Do not select the Kids category.
Store price, territories, copyright holder, and release version are human-owned
decisions; the product requirement is that all protection levels remain free.
The baseline's `0.1.0` / build `1` is not an approved v1 release number
([project settings](../../../ios-app/project.yml#L23-L31)).

### Name

```text
Flint
```

### Subtitle

```text
Make room for focused time
```

### Keywords

```text
screen time,blocker,distraction,study,work,timer,routine,concentration,offline,privacy
```

### Description

```text
Make time for what matters with Flint, a quiet space for more intentional phone use.

CHOOSE WHAT RESTS
Use Apple's Screen Time picker to choose apps and websites for a focus session. Pick a duration, choose a protection level, and begin. Save your selections as named groups so you can use them again.

MAKE FOCUS A ROUTINE
Set recurring schedules and configure app time limits around your day. Start with a simple session, then choose the routine that fits how you work or study.

KEEP IT PERSONAL
Flint needs no Flint account and includes no analytics or advertising SDKs. Your selections and settings are stored on your device and shared locally with Flint's Screen Time extensions. There is no Flint cloud sync. All protection levels are free.

KNOW WHAT YOU ARE CHOOSING
Flint uses Apple's Screen Time permission to apply restrictions. Access can be reviewed in iOS Settings. Protection depends on the permissions and settings on your device. Stronger sessions can also temporarily restrict app removal across the device; read the in-app explanation before starting one.

Flint is open source. Take a little space for the next thing you want to do.
```

Source for the proposed workflows:
[picker, saved groups, and duration](../../../ios-app/Flint/Features/Today/StartFocusSheet.swift#L1-L164),
[recurring schedules](../../../ios-app/Flint/Features/Schedules/SchedulesView.swift#L396-L446),
[limits persistence](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintGroupStore.swift#L135-L144),
[individual authorization](../../../ios-app/Flint/Features/Authorization/AuthorizationModel.swift#L30-L40),
and [device-wide removal restriction](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintUninstallGuard.swift#L16-L28).
Source shows intended behavior; lane B and physical verification must establish
that the final copy describes the shipped behavior. Website selection is distinct
from Safari content/Private Browsing settings; neither implies universal browser
coverage. This copy intentionally omits Open Limit timing, Sleep automation,
Shortcuts, Focus filters, session history, and usage statistics until their gates
pass. Do not infer completed integrations from their presence in Settings.

### Length validation

Apple's [product-page guidance](https://developer.apple.com/app-store/product-page/)
sets 30-character name/subtitle limits. The more specific
[platform version reference](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)
sets 4,000 characters for the description and 100 bytes for keywords. Use the
byte limit for keywords; the proposed keywords are ASCII, comma-separated,
unique, and contain no competitor names. Check the four blocks again after any
wording or localization change. App Review Notes have a separate 4,000-byte
limit, checked in the companion document. No promotional text is proposed.

D6 measured the exact blocks above: name **5 characters**, subtitle **26
characters**, keywords **86 UTF-8 bytes**, description **1,154 characters**.
All four are ASCII, so byte and character counts coincide for this draft.

## App Privacy answer draft: Data Not Collected

**Proposed answer:** neither the developer nor third-party partners collect data
from the iOS app. Expected label: **Data Not Collected**. Tracking: **No**.
No data types, linked-to-user purposes, or tracking purposes are proposed.
This is a source-backed draft requiring final release dependency/resource and
runtime checks, not a statement that the App Store Connect answers are saved.

Apple's [App Privacy guidance](https://developer.apple.com/app-store/app-privacy-details/)
distinguishes on-device processing from developer/partner collection and requires
accounting for third-party components. Local use of Screen Time data is still
privacy-sensitive; a label does not mean the app has no local data.

### Source map and boundaries

- **Dependencies and upload paths:**
  [project packages](../../../ios-app/project.yml#L33-L35) and
  [FlintCore Package.swift](../../../ios-app/Packages/FlintCore/Package.swift#L4-L13)
  declare a local package with no external package dependency. Inspection of
  production Swift, YAML, plists, and entitlements found no app-owned URLSession
  client or analytics, advertising, crash-upload, or account SDK. Re-run the
  inventory against all five archived bundles and linked frameworks after merges;
  absence of a source match alone is not proof of zero runtime network traffic.
  iOS has no Android-style INTERNET permission switch; do not cite Android's
  release manifest as iOS privacy evidence.
- **Selected apps, categories, and websites:**
  [FlintGroupStore](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintGroupStore.swift#L112-L154)
  encodes opaque FamilyActivitySelection tokens, rules, limits, and named groups
  in shared defaults. Group names are local user-entered text. Tokens are not a
  developer-maintained list of raw app identifiers. No token export is proposed.
- **Session and protection state:**
  [shared keys and suite](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintGroupStore.swift#L10-L32)
  and [state persistence](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintGroupStore.swift#L46-L110)
  keep active-session times, pass-use timestamps, Open Limit counters, and arming
  health locally in `group.com.flint.peakfocus`. Historical session encode/decode
  helpers exist at lines 37–43, but have no production caller at this baseline;
  do not turn that API into a history claim. Lane F must refresh this map when
  its local event store lands, including storage/retention/deletion behavior.
- **PIN:** [FlintPIN.set/verify](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintPIN.swift#L19-L42)
  stores a salted hash through the shared defaults, not a plaintext PIN or a
  server credential. Do not market this as encrypted storage or tamper-proof
  security. An app-open PIN is distinct from Apple's Screen Time passcode.
- **Website rules and routine settings:**
  [website settings persistence](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintWebRestrictions.swift#L169-L187)
  stores user-entered domains locally;
  [Sleep, Focus, and Pomodoro settings](../../../ios-app/Packages/FlintCore/Sources/FlintCore/FlintModel.swift#L217-L264)
  use the same App Group defaults. A domain rule is not a browsing-history feed.
- **Screen Time report data:**
  [TotalActivityReport.makeConfiguration](../../../ios-app/Extensions/DeviceActivityReportExtension/TotalActivityReport.swift#L14-L38)
  aggregates Apple-supplied durations, pickups, and localized app names inside
  the report extension. [ActivitySummary](../../../ios-app/Extensions/DeviceActivityReportExtension/ActivitySummary.swift#L3-L22)
  is report-private and in memory; source does not persist or forward its raw
  report values to the host or a backend. Keep lane F's local session events
  separate from Screen Time usage data; do not export usage to fill empty cards.
- **External destinations:**
  [About Flint](../../../ios-app/Flint/Features/Settings/SettingsView.swift#L46-L48)
  opens the repository when chosen by the user;
  [authorization recovery](../../../ios-app/Flint/Features/Authorization/AuthorizationControls.swift#L15-L34)
  opens iOS Settings. A browser, hosting provider, Apple, or a future support
  contact can process data under their own practices. Do not promise that those
  services receive nothing. Audit new support links, forms, server logs, and
  voluntarily sent attachments with lane E before finalizing disclosures.

The answer must be reassessed if any lane adds collection, export, a remote
service, or third-party code. Cloud backup, device transfer, and Apple system
diagnostics are separate from a Flint backend; do not promise that OS backups
never contain local settings or that uninstall removes backups. Verify removal
of the host and its extensions/shared container on a test device before writing
deletion instructions. Do not invent an in-app reset control.

App Privacy answers are separate from required-reason API declarations. Lane D's
privacy manifests must cover actual APIs/resources in every shipped bundle;
local UserDefaults use still needs the appropriate manifest review. D6 has not
validated a signed archive, manifests, entitlements, or DEBUG-fixture exclusion.

## Age-rating rationale for owner entry

**Provisional expectation: 4+ for ordinary territories**, based on a productivity
utility with no objectionable supplied content. This is an inference, not an
assigned age rating, an age-assurance claim, or approval to market to children.
Apple calculates ratings from the owner's answers and may show different
regional/older-OS results. Use the current
[rating definitions](https://developer.apple.com/help/app-store-connect/reference/app-information/age-ratings-values-and-definitions/)
and [questionnaire workflow](https://developer.apple.com/help/app-store-connect/manage-app-information/set-an-app-age-rating/)
at submission; do not reuse an obsolete 12+/17+ worksheet.

- **Parental controls: proposed No.** Current
  [authorization is `.individual`](../../../ios-app/Flint/Features/Authorization/AuthorizationModel.swift#L30-L40),
  for self-management. There is no parent/child account, family dashboard, or
  remote child supervision. The FamilyControls framework name alone is not a
  questionnaire answer. Reassess if the release adds child-directed controls.
- **Age assurance: No.** No age verification/estimation or Declared Age Range
  integration was found in the inspected iOS graph.
- **Unrestricted web access: No.** The app configures restrictions and opens an
  external About link; it does not contain a general-purpose browser or WKWebView.
  Confirm all final support/privacy links remain external and are not embedded
  browsing surfaces before keeping this answer.
- **User-generated content, messaging/chat, social media, advertising: No.**
  Local names for rules/groups are not a shared feed. No social, ad, or messaging
  service is included in this iOS source graph.
- **Contests, loot boxes, simulated gambling, gambling: None/No**, as the live
  form offers. There are no purchases, randomized rewards, or competitions.
- **Profanity/crude humor; horror/fear; alcohol, tobacco or drugs; mature or
  suggestive themes; sexual content/nudity; graphic sexual material; cartoon,
  fantasy, realistic or graphic violence; weapons: None**, as applicable to each
  live question. Use benign sample names and screenshots.
- **Medical/treatment information: None; health/wellness topics: proposed No.**
  Focus timers and a bedtime schedule are utilities, not medical or wellness
  advice. The proposed listing makes no treatment/ADHD/addiction/health claims.
  If final Sleep/onboarding/support content adds wellness education, reassess:
  Apple's current definitions can raise the rating for wellness content.

The owner must inspect every final prompt, retain the computed regional results,
and decide any age override. Do not claim that a 4+ expectation makes this a Kids
category app or that every device/account can authorize Screen Time.

## Real screenshot shot-list — no assets captured

All shots are **pending**. Capture the actual signed candidate on owner-approved
physical devices with neutral test selections and real test activity. Never use
`-FlintUIPreviewSession`, authorization previews, UI fixtures, fabricated report
values, synthetic streaks, design HTML, or AI-generated pixels as product proof.
The [session preview](../../../ios-app/Flint/Features/Sessions/SessionViewModel.swift#L31-L45)
and [authorization preview](../../../ios-app/Flint/Features/Authorization/AuthorizationModel.swift#L30-L37)
are DEBUG-only source paths whose exclusion still needs release verification.

For each capture, record candidate commit, version/build, signed archive hash,
device model/OS, native dimensions, locale, capture date, scenario steps, raw PNG
hash, and caption approval in the release evidence record. Use fresh sample
activity on a dedicated test device; redact by recapturing clean state rather
than inventing content. Do not include personal notifications, contacts, PINs,
private website lists, or unrelated people’s activity.

1. **Start a focus session:** Today → Start focus, selected apps and duration,
   benign group name such as “Study”. Caption: “Choose what rests.” Gate: picker
   selection persists and starts a real session (lane B, script R3).
2. **Active session:** real countdown and chosen level. Caption: “Make room for
   focused time.” Gate: selected test app actually shields and unselected safe
   app remains available (lane B, R3–R4). Countdown alone is insufficient.
3. **System shield:** launch the selected test app during the session and capture
   the real shield supplied by the configuration extension. Caption: “A pause
   before the next tap.” Gate: physical configuration/action callbacks observed
   (lane B, R4). Never composite the shield into an app screenshot.
4. **Recurring routine:** Rules → Schedules, saved benign recurring rule. Caption:
   “Build a routine around your day.” Gate: real start/end boundaries and overlap
   behavior (lane B, R6). Do not imply arbitrary one-shot future scheduling.
5. **Time limits:** Rules → time-limit editor with test app and real allowance.
   Caption: “Set an intentional limit.” Gate: actual threshold, release/reset,
   and competing restriction behavior (lane B, R6). Omit Open Limits until its
   bounded access and re-shield behavior are accepted.
6. **You / privacy:** show actual local-only privacy/settings screen. Caption:
   “No Flint account. No analytics.” Gate: final archive and dependency/privacy
   check. Current UI's “history” text must be reconciled with lane F before capture.
7. **Optional Insights / completion:** only after lane F and lane B link real
   local-event/report evidence. Caption must describe the data actually shown;
   use true empty/no-data state if no report is available. Never turn blank
   report output into “zero use” or “time saved”. Omit this shot if unverified.

Repeat approved shots using actual iPad layout: the
[device-family setting](../../../ios-app/project.yml#L31)
includes iPhone and iPad. Apple's
[current screenshot specifications](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications/)
list required iPhone and 13-inch iPad display coverage and accepted pixel sizes.
At preparation time, medium Dynamic Island iPhone portrait sizes include
1179×2556 and 1206×2622, and 13-inch iPad portrait sizes include 2064×2752 and
2048×2732. Choose the matching native capture; recheck the live spec/Connect
slots rather than stretching an image or assuming one iPhone set covers iPad.
No screenshots, preview video, or upload-ready asset set accompanies this draft.

## Public privacy/support URL plan — lane E dependency

The canonical policy is lane E's planned `docs/privacy-policy.md`, linked from
the [v1 lane plan](../../roadmap/v1-build-plan.md). It is absent at this D6
baseline. Link the merged policy here and reconcile the iOS source map before
submission; D6 does not create or edit that lane's file.

- **Privacy Policy URL:** human to choose and publish a stable public HTTPS page
  rendered from lane E's policy. A repository path is not a published policy URL.
  No domain or hosting account is asserted or reserved by this document.
- **Support URL:** human to publish a public HTTPS page with actual owner-approved
  contact information, permission/recovery help, protection limitations, and
  safe removal guidance. Do not use the About repository link as a substitute
  without confirming it meets the support/contact requirement. Include a way
  to contact support privately without sharing Screen Time selections publicly.
- **Hosting/privacy reconciliation:** choose hosting and contact handling with
  lane E; document any access logs, retention, forms, and optional email handling
  accurately. Do not silently add tracking, analytics, or a required account.
- **Acceptance:** open both final URLs without signing in; verify HTTPS, mobile
  readability, correct Flint identity/contact, policy date and iOS coverage,
  functioning links, and consistent in-app access on the final candidate.
  Save final URLs and checks in release evidence, then the human enters them in
  Connect. Site publication and in-app link implementation are not done here.

Apple requires a public policy per its
[privacy details guidance](https://developer.apple.com/app-store/app-privacy-details/)
and a functioning support/contact destination per the
[platform version reference](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/).
Public Apple guidance was read on 2026-10-28; no signed-in account state was read.
