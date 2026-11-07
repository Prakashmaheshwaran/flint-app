# Developer accounts → first store submission

Status snapshot for Flint's two store accounts, the exact actions that remain on each, and the
repo changes that remove the code-side blockers. Written 2026-11-01.

Companion files: [`privacy-policy.md`](privacy-policy.md) (both stores require the URL),
[`../architecture/00-architecture-decisions.md`](../architecture/00-architecture-decisions.md)
(ADR-005/006/008 explain the 5 iOS targets and the placeholder identifiers).

## What this pass could and could not do

The Apple Developer Program and Play Console are behind your Apple ID and Google account, with
two-factor authentication. No credentials for either were available, and creating them or
signing in on your behalf is not something an agent should do. So:

| Done in this repo | Done in the portals (this pass) | Still yours |
| --- | --- | --- |
| Android `compileSdk`/`targetSdk` → 36 | Program License Agreement accepted | App Store Connect agreements |
| Play upload-key path + `make android-bundle` | 5 App IDs + App Group registered | Capability toggles per App ID |
| iOS privacy manifest in all 5 bundles | Family Controls request filed (`2CLYP9S4H2`) | Play app record + upload key |
| iOS icon + export compliance + team xcconfig | Identifiers re-keyed to `app.flint.focus` | Accessibility declaration + video |
| Privacy policy draft | | Play 12-tester closed test |
| Release checklist below | | Store listings, screenshots, Data safety |

Total portal work is roughly 2–4 hours of clicking plus two waits you cannot compress:
Apple's Family Controls review (days → weeks) and Play's 14-day closed test (personal accounts).

## Identifiers — one source of truth

| Thing | Value |
| --- | --- |
| Apple Team ID | `5BWQYWJ3Z4` (also in the git-ignored `ios-app/Config/Local.xcconfig`) |
| Apple account type | Individual — seller name is your legal name |
| App Store record | `Flint: Peak Focus` · App Store ID `6829519334` · SKU `flint-ios-001` |
| Family Controls request | Filed 2026-11-01 · **Request ID `2CLYP9S4H2`** |
| iOS host bundle ID | `app.flint.focus` |
| iOS extensions | `.monitor`, `.shield-config`, `.shield-action`, `.report` |
| iOS App Group | `group.app.flint.focus` |
| Android application ID | `app.flint.focus` |
| Android version | `versionCode 1`, `versionName 0.1.0` |
| iOS version | `MARKETING_VERSION 0.1.0`, build `1` |

**The original placeholder prefix was unusable.** `com.flint.peakfocus` and
`group.com.flint.peakfocus` are already registered by another team — Apple rejects both at
registration because bundle IDs and App Group IDs are globally unique. Everything was re-keyed to
`app.flint.focus` in `project.yml`, the five entitlements, `FlintGroupStore.appGroupID`, and the
Android `applicationId`. Do not reintroduce the old string.

Registration and capability state as of 2026-11-01: all **five App IDs** and the **App Group**
exist, and **all five carry App Groups (`group.app.flint.focus`) and Family Controls
(Development)**, each verified by reopening the edit page and reading back the state:

| App ID | Capabilities saved |
| --- | --- |
| `app.flint.focus` (host) | App Groups (1) + Family Controls (Development) |
| `app.flint.focus.monitor` | App Groups (1) + Family Controls (Development) |
| `app.flint.focus.report` | App Groups (1) + Family Controls (Development) |
| `app.flint.focus.shield-action` | App Groups (1) + Family Controls (Development) |
| `app.flint.focus.shield-config` | App Groups (1) + Family Controls (Development) |

The **distribution** capability (`FAMILY_CONTROLS_DISTRIBUTION`) stays off until Apple approves
request `2CLYP9S4H2`; only the development capability is enabled now.

Xcode's automatic signing can manage the same state, but on this machine
`xcodebuild -allowProvisioningUpdates` fails with **"No Accounts: Add a new account in Accounts
settings"** for all five targets. Sign in once at Xcode → Settings → Accounts (2FA required) before
archiving from the command line.

Note: new App IDs in this portal come up with **In-App Purchase already enabled**, and Apple does
not allow disabling that capability. It is harmless for a free app with no products; it does mean
the App ID is flagged for IAP if you ever add it.

## Verified platform requirements as of 2026-11-01

| Requirement | Deadline | State |
| --- | --- | --- |
| Play: new apps and updates target **API 36** (Android 16) | 2026-08-31, extension to 2026-11-01 | Fixed in this pass |
| Play: new apps ship as **AAB**, not APK | already in force | `make android-bundle` |
| Play: **AccessibilityService** apps submit the declaration form + demo video | already in force | Needed at submission |
| Play: personal accounts created after 2023-11-13 run a **closed test with 12 testers opted in for 14 consecutive days** before applying for production | already in force | Not started — confirmed personal account |
| App Store: builds uploaded must use **Xcode 26+ / iOS 26 SDK** | 2026-04-28 | Xcode 27.2 installed; OK |
| App Store: Family Controls distribution entitlement | before distribution | **Requested** 2026-11-01 (account-level form, ID `2CLYP9S4H2`) |

Sources: Google Play target-API page, Play accessibility policy, Play closed-testing page, Apple
"Upcoming requirements". Re-check them at submission time; all four change yearly.

## Apple — actions in order

### 1. Account-level (30 minutes, once)

- [x] Signed in and **accepted the updated Program License Agreement (15.9.2026)** on
      2026-11-01. Before that, Certificates, Identifiers & Profiles and App Store Connect were
      gated.
- [ ] In App Store Connect → **Business** (Agreements, Tax, and Banking), accept the current
      agreements. Free apps do not need tax or banking details; skip those unless you plan to
      sell or add in-app purchases.
- [ ] Confirm 2FA is on for the Apple ID (required for the program).
- [x] Team ID recorded in `ios-app/Config/Local.xcconfig` (git-ignored), not in `project.yml`.
- [ ] Decide the public seller name now. **Individual** enrollment shows your legal name as the
      seller and is hard to change later; **Organization** enrollment requires a D-U-N-S number
      and shows the company name. This account is enrolled as **Individual**. Switching after
      shipping means a new account and losing the bundle IDs' history.

### 2. Identifiers and capabilities

- [x] Registered **5 explicit App IDs** (`app.flint.focus` plus `.monitor`, `.shield-config`,
      `.shield-action`, `.report`) on 2026-11-01.
- [x] Created the App Group `group.app.flint.focus`.
- [x] **App Groups** and **Family Controls (Development)** enabled on all five App IDs, with
      `group.app.flint.focus` ticked on each (verified by reopening every edit page). The
      distribution capability stays off until Apple approves the request.
- [x] Team configured once, outside git (`Config/Base.xcconfig` includes the git-ignored
      `Config/Local.xcconfig`), so `make ios-gen` no longer wipes it.

### 3. Family Controls distribution entitlement — the critical path

This is the item that decides your launch date. Apple gates App Store distribution of the
Screen Time APIs.

- [x] **Filed 2026-11-01 — Request ID `2CLYP9S4H2`.**
- [x] Apple's current form is **account-level**, not one-per-bundle-ID: it prefilled the account
      name, email, and Team ID, and submitting returned a single request ID. Do not file four more
      unless Apple asks for them by bundle ID.
- [ ] Expect days to weeks. You can build and TestFlight in the meantime; only App Store
      distribution waits.
- [ ] If Apple follows up, reuse the description in Appendix A — it matches what the code does.

### 4. Signing and CI credentials (15 minutes)

- [ ] For local uploads, Xcode's automatic signing with your team is enough.
- [ ] For CI uploads, create an **App Store Connect API key**: App Store Connect → Users and
      Access → Integrations → App Store Connect API → Team Keys → generate with the **App
      Manager** role. Download the `.p8` once; record Key ID and Issuer ID.
- [ ] Never commit the `.p8` — `.gitignore` now blocks it. Suggested GitHub secrets:
      `APPSTORE_API_PRIVATE_KEY`, `APPSTORE_API_KEY_ID`, `APPSTORE_API_ISSUER_ID`.

### 5. App record and listing (45 minutes)

- [x] App Store Connect Terms of Service accepted and the app record created on 2026-11-01:
      **`Flint: Peak Focus`**, App Store ID **`6829519334`**, SKU `flint-ios-001`, iOS, Free,
      Full Access.
- [x] "Flint" alone is **already taken** on the App Store ("The app name you entered is already
      being used"). The listing is therefore `Flint: Peak Focus`; the home-screen name stays
      `Flint` (`INFOPLIST_KEY_CFBundleDisplayName`).
- [ ] App Information: primary category (Productivity or Health & Fitness both fit), content
      rights, age rating.
- [ ] Pricing: Free.
- [ ] **App Privacy**: Data Not Collected, no tracking. This matches `PrivacyInfo.xcprivacy`.
- [ ] Privacy Policy URL: host [`privacy-policy.md`](privacy-policy.md) publicly — GitHub Pages
      from this repo is enough. Play requires the same URL.
- [ ] Screenshots: the project targets iPhone **and** iPad (`TARGETED_DEVICE_FAMILY 1,2`), so both
      are required — 6.9"/6.5" iPhone and 13" iPad at minimum, plus 5.5" if you want older
      layouts.
- [ ] iPad orientation: the project currently declares **portrait only**
      (`INFOPLIST_KEY_UISupportedInterfaceOrientations`). App Review expects iPad apps to support
      all four orientations unless the app requires full screen. Either add
      `INFOPLIST_KEY_UISupportedInterfaceOrientations_iPad` with all four (and check the layouts),
      or declare a full-screen requirement — decide before the first archive, because it is a
      product decision, not a build fix.
- [ ] App icon: already generated at
      `ios-app/Flint/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`
      (1024×1024, no alpha). App Store Connect does not take an icon file separately — it comes
      from the build.
- [ ] Export compliance: already answered in the build
      (`ITSAppUsesNonExemptEncryption = NO`, verified in the built `Info.plist`) because Flint has
      no networking and only hashes a local PIN. Confirm App Store Connect shows it rather than
      asking again.
- [ ] App Review notes: state that blocking needs the Screen Time permission, that all five
      targets exist for the Screen Time API, and include the Family Controls approval reference.
      Reviewers must be able to exercise the app on a device with Screen Time available.

### 6. Build and upload

- [ ] `make ios` → select the Flint scheme and a real device (Screen Time does not work in the
      Simulator).
- [ ] Product → Archive → Distribute App → App Store Connect → Upload. Xcode Organizer is the
      simplest upload path; CI can use `xcrun altool --upload-app` with the API key above.
- [ ] TestFlight internal testers first. External TestFlight needs a short beta review.
- [ ] Submit for review only after all five entitlement requests are approved.

## Google Play — actions in order

### 1. Account-level — **NOT complete, and it blocks everything else**

Entered the console on 2026-11-01. Home shows a **personal account**, ID
`7244623482324235479`, and a banner: *"Finish setting up your developer account… to publish apps
on Google Play."* Two items are marked **Action required**, and the **Create app** button is
disabled until they clear:

- [ ] **Verify your identity** — *Get started*. Upload an official identity document. Google's
      guidance: do not edit or manipulate the document, or verification fails in a way that can
      affect the availability of the account.
- [ ] **Verify your contact phone number** — *View details*, and complete the SMS verification.

Both need you: a government ID and a code sent to your phone. Nothing in this repo or any
automation can supply them.

- [ ] Accept the current **Developer Distribution Agreement**.
- [ ] Confirm the developer profile that will be public (name, contact email). Personal accounts
      also show a verified address; organization accounts do not.
- [ ] Payments profile: not needed for a free app. Skip unless you sell something.
- [ ] Confirm 2FA on the Google account.
- [x] **Account type confirmed: personal** — so the closed test in §4 applies.

### 2. Create the app and the upload key (20 minutes)

> **Entering Play Console needs a real click.** The account chooser accepts only a trusted user
> gesture: scripted clicks, dispatched pointer events, and direct
> `/developers/<id>/app-list` URLs all bounce back to "Choose developer account". Do it in the
> same Chrome window the automation is attached to, then hand back.

- [ ] Play Console → **Create app**: name, default language, App (not Game), Free, accept the
      declarations. Package name `app.flint.focus` is permanent once the first artifact
      uploads.
- [ ] Create the upload key locally:

      make android-upload-key

  It writes `android-app/flint-upload.keystore` and the git-ignored
  `android-app/keystore.properties`, and prints the SHA-256. Back both up now — after the first
  upload, a lost upload key needs a Play support reset.
- [ ] Build the Play artifact:

      make android-bundle
      # → android-app/app/build/outputs/bundle/release/app-release.aab

  With no `keystore.properties`, the same task falls back to the debug key so preview APKs still
  build anywhere — Play uploads must use the generated key.
- [ ] Upload the AAB to a testing track. This enrols it in **Play App Signing**; Google keeps the
      app-signing key and this upload key only signs your uploads.

### 3. Policy declarations — the review risk

Flint uses the AccessibilityService API to detect and cover blocked apps. Play treats that as a
"monitoring app", so it must **not** claim `isAccessibilityTool`, and it must ship a prominent
disclosure with affirmative consent before the settings hand-off. The code already does this
(`blocking-accessibility` and `feature-onboarding`); the portal work decides whether review
passes.

- [ ] App content → **Accessibility** declaration: explain why the API is needed, that usage is
      on-device only, and list the data types involved.
- [ ] Record the required video: the in-app disclosure, the consent tap, the hand-off to
      Android's accessibility settings, what happens on refusal (and that the prompt reappears),
      then a blocked app being covered. Without the video the declaration is incomplete.
- [ ] App content → **Data safety**: no data collected or shared. On-device processing is not
      collection. Keep this consistent with the privacy policy.
- [ ] App content → **Content rating** (IARC questionnaire), **Ads: no**, target audience, and
      the **special-use foreground service** justification — the manifest already declares
      `foregroundServiceType="specialUse"` with the subtype property; restate that text in the
      form.
- [ ] Confirm the release build requests no `INTERNET` permission. `make android-release` runs
      `scripts/verify_local_first_apk.py`, which fails the build if account code or network
      permissions sneak in.

**Two existing declarations to expect questions about** (both are real product needs, not
mistakes — have the justification ready rather than removing them late):

- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`. Play restricts the direct exemption request to
  categories like alarms and VoIP. Blocking apps do use it to survive OEM killers, but review may
  ask for justification or prefer the settings-screen hand-off
  (`ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS`). Decide before submission whether to keep the
  direct request or route users to Settings; changing it later means another review.
- `SYSTEM_ALERT_WINDOW` plus the accessibility overlay. Both enforcement paths are already
  described in the disclosure, so the declaration should name them together: the accessibility
  service detects the foreground app, the overlay draws the block screen.

### 4. Testing → production

- [ ] Upload to **internal testing** (up to 100 testers) and smoke-test on a real device.
- [ ] Personal account: upload to **closed testing**, recruit **at least 12 testers who stay
      opted in for 14 consecutive days**, then apply for production access from the dashboard
      and answer the questions about the test.
- [ ] Organization account: you can go straight to production review.
- [ ] Store listing before review: icon 512×512 PNG, feature graphic 1024×500, at least 2 phone
      screenshots plus 7" and 10" tablet screenshots, short description (≤80 chars), full
      description, privacy policy URL, contact details.
- [ ] Optional but useful: Play pre-launch report and a staged rollout for the first production
      release.

## Sequencing that respects the two waits

1. **Done 2026-11-01:** agreement accepted, five App IDs and the App Group registered,
   Family Controls request `2CLYP9S4H2` filed.
2. **Next:** create the Play app record (package `app.flint.focus`) and the upload key; start the
   Play closed test — the 14-day clock only runs while 12+ testers are opted in, and it needs a
   reviewed build.
3. **While waiting:** capability toggles per App ID, store listings, screenshots, Data safety /
   App Privacy, privacy policy hosted, TestFlight internal builds.
4. **After Apple approves the entitlement:** archive, upload, external TestFlight, submit.
5. **After the Play test completes:** apply for production, then roll out.

## Secrets inventory

| Secret | Where it lives | Never |
| --- | --- | --- |
| Play upload keystore + passwords | `android-app/flint-upload.keystore`, `android-app/keystore.properties` | Committed, archived, or pasted into chat |
| Apple Distribution cert / profiles | Xcode keychain, or CI secrets | Committed |
| App Store Connect API `.p8` | CI secret store; `.gitignore` blocks `*.p8` | Committed |
| Play service-account JSON (optional, for CI uploads) | CI secret store; `.gitignore` blocks `play-service-account*.json` | Committed |

## Repo state after this pass

- **Identifiers re-keyed to `app.flint.focus`** (iOS bundle prefix, App Group, Android
  applicationId) because the previous placeholder prefix belonged to another team. Verified in
  the built app's `CFBundleIdentifier`.
- Android targets API 36 and builds a Play-signed AAB when a local upload key exists.
- `make android-upload-key`, `make android-bundle` are documented in `make help`.
- Regression test: `scripts/test_setup_android_upload_key.py` (runs inside `make selftest`).
- iOS ships a privacy manifest in all five bundles and a 1024 App Store icon; both verified in
  the built `.app`.
- The signing team now lives in the git-ignored `ios-app/Config/Local.xcconfig` (Team
  `5BWQYWJ3Z4`), included optionally by `Config/Base.xcconfig`, so `make ios-gen` no longer wipes
  it.
- Export compliance is answered at build time (`ITSAppUsesNonExemptEncryption = NO`).
- Privacy policy draft lives in this directory; the contact email is the one thing still to fill
  in before hosting it.

Remaining: the Play app record and its 14-day closed test, store listings, and waiting out
Apple's entitlement review.

## Appendix A — paste-ready Family Controls request

Apple's current form is account-level: it prefills name, email, and Team ID and issues one
request ID. Keep this text for any follow-up Apple sends.

> **App:** Flint — Peak Focus
> **Bundle ID:** `app.flint.focus` (or `.monitor`, `.shield-config`, `.shield-action`, `.report`)
> **Team ID:** `<your Team ID>`
> **Website / source:** https://github.com/Prakashmaheshwaran/flint-app
>
> Flint is a user-configured focus and screen-time app. The user chooses which apps and websites
> to block and when; Flint then applies Apple's shield over that selection during focus sessions,
> schedules, and time limits, and shows the user a summary of their own usage.
>
> The app is one host app plus four extensions: the host app presents Apple's Family Controls
> authorization prompt and the app picker; DeviceActivityMonitor applies and clears shields at
> schedule and threshold boundaries so blocking survives app termination; ShieldConfiguration
> renders the block screen; ShieldAction handles its buttons; DeviceActivityReport renders the
> per-app usage summary, which is the only sanctioned source for that UI.
>
> There is no account, no server, and no analytics. The FamilyActivitySelection is stored in a
> private App Group container on the device and is never transmitted. Flint has no network code
> and no third-party SDKs. Blocking is always the result of an explicit user choice, shown in the
> UI, and can be turned off at any time in iOS Settings → Screen Time and inside the app.
>
> A TestFlight build can be provided to demonstrate the authorization flow, app selection,
> shielded launch, and usage report.

## Appendix B — paste-ready Play accessibility declaration

> Flint blocks apps the user explicitly puts on a focus blocklist. The AccessibilityService is
> used only to detect when a user-selected app comes to the foreground; Flint then draws its own
> block screen until the user ends the session, takes a break, or the focus rule expires. The
> service does not initiate actions, navigate other apps, or read screen content beyond the
> package name of the foreground window.
>
> All processing is on-device. No data leaves the device: the release build does not request the
> INTERNET permission and contains no analytics or advertising SDKs.
>
> The in-app disclosure appears before the system accessibility settings hand-off, states what is
> accessed and why, and requires an affirmative tap. Declining keeps the app usable without
> blocking and the prompt reappears the next time the user tries to enable blocking. The attached
> video shows the disclosure, consent, enabling the service, refusal and re-display, and a blocked
> app being covered.
