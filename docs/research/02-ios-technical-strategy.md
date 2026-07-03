# Flint — iOS Technical Strategy: The Screen Time API Blocker

> The definitive implementation plan for Flint's app/website blocker on iOS and iPadOS,
> built entirely on Apple's Screen Time API stack (FamilyControls, ManagedSettings,
> ManagedSettingsUI, DeviceActivity). This document is the source of truth for the
> framework stack, the required app + extension targets, the entitlement reality for
> an open-source / free app, the minimum OS version, what is genuinely possible vs.
> impossible, and the recommended `ios-app/` target and module layout.

---

## 1. Executive summary

Flint blocks apps and websites on iOS the **only** way Apple permits a third party to do
so: through the **Screen Time API** (introduced WWDC21, matured WWDC22). There is no
private API, no MDM-free network filter, and no way around the system. This is the same
stack Opal, one sec, Jomo, and every other blocker is built on, which means **Flint can
match Opal feature-for-feature** — the limits are Apple's, not a paywall.

Three load-bearing realities shape everything below:

1. **The whole stack is gated behind one entitlement** — `com.apple.developer.family-controls`
   — which is free for development but **requires explicit Apple approval to ship on the
   App Store**. This approval is granted per-Apple-ID via a request form, is **not blocked
   for free or open-source apps**, and a GitHub repo URL has been accepted as the required
   "website." This is the single biggest distribution risk and it is surmountable.

2. **The blocker logic does not live in the app.** It lives in **system app extensions**
   (`DeviceActivityMonitor`, `ShieldConfiguration`, `ShieldAction`, `DeviceActivityReport`)
   that the OS launches on its own schedule. The app is mostly a configuration and
   presentation shell. Plan the target layout around the extensions from day one.

3. **App/website identities are opaque tokens.** Flint can never read a selected app's
   bundle ID, name, or icon. Tokens are chosen by the user through a system picker, are
   `Codable` for storage, render only via system-provided SwiftUI labels, and **rotate
   over time**. Design every feature assuming you hold a sealed handle, not an identity.

---

## 2. Framework stack

All four frameworks ship in the iOS SDK; none are third-party.

| Framework | Role in Flint | Min iOS |
| --- | --- | --- |
| **FamilyControls** | Authorization gate (`AuthorizationCenter`), the privacy-preserving app/website picker (`FamilyActivityPicker` / `FamilyActivitySelection`), and the opaque token types (`ApplicationToken`, `ActivityCategoryToken`, `WebDomainToken`). | 15.0 (framework); **16.0 for `.individual`** |
| **ManagedSettings** | The enforcement layer. `ManagedSettingsStore` holds the `shield.*` properties that cause the OS to overlay a block screen on selected apps/sites/categories. | 15.0 (named stores, `clearAllSettings`, web-domain shielding → **16.0**) |
| **ManagedSettingsUI** | The block-screen UI. `ShieldConfigurationDataSource` (look of the shield) and `ShieldActionDelegate` (button taps). | **16.0** |
| **DeviceActivity** | Scheduling and triggering. `DeviceActivityCenter` registers `DeviceActivitySchedule`s and `DeviceActivityEvent`s; the `DeviceActivityMonitor` extension receives the callbacks. `DeviceActivityReport` renders usage data. | 15.0 (Report extension → **16.0**) |

The flow at runtime:

```
FamilyControls authorization (.individual)
        │  user must approve via Face ID / Touch ID / passcode
        ▼
FamilyActivityPicker  ──►  FamilyActivitySelection (opaque tokens)
        │                          │  Codable → App Group storage
        ▼                          ▼
DeviceActivityCenter         ManagedSettingsStore.shield.*
  schedules / events                 │
        │                            ▼
        ▼                     OS draws the shield overlay
DeviceActivityMonitor ext  ──► writes/clears shields on boundaries
        │
        ▼
ShieldConfiguration ext (look)  +  ShieldAction ext (taps)
DeviceActivityReport ext (usage stats UI)
```

---

## 3. Required targets: the app + its extensions

The Screen Time API forces a multi-target Xcode project. The host app **cannot** do the
blocking itself — the OS only ever talks to the extensions. Each row below is a separate
build target with its **own bundle ID** and (for distribution) its **own entitlement
request**.

### 3.1 Host app (`Flint`)
The SwiftUI app. Responsibilities:
- Request and observe `AuthorizationCenter.shared.authorizationStatus`.
- Present the `FamilyActivityPicker` and persist the resulting `FamilyActivitySelection`.
- Build the user's focus sessions / schedules / limits UI.
- Call `DeviceActivityCenter().startMonitoring(...)` / `stopMonitoring(...)`.
- Embed a `DeviceActivityReport` SwiftUI view to show usage stats.
- It does **not** itself need to be running for blocking to work — the OS drives the
  extensions independently.

### 3.2 `DeviceActivityMonitor` extension (REQUIRED)
- Subclass `DeviceActivityMonitor`. Override `intervalDidStart`, `intervalDidEnd`,
  `eventDidReachThreshold`, and the warning variants.
- This is where you **apply and clear shields** on schedule boundaries: read the saved
  `FamilyActivitySelection` from the App Group, then set
  `store.shield.applications = selection.applicationTokens`, etc., on start and `nil` on
  end. Writing shields from the extension (not the app) is the documented best practice —
  it keeps enforcement alive when the app is closed/killed.
- Runs in a tightly memory-constrained, short-lived sandbox.

### 3.3 `ShieldConfiguration` extension (REQUIRED for branded shields)
- Subclass `ShieldConfigurationDataSource`; override
  `configuration(shielding application:)`, `configuration(shielding webDomain:)`, and the
  category variants. Return a `ShieldConfiguration` (background blur/color, icon, title,
  subtitle, primary/secondary button labels and colors).
- This is how Flint replaces Apple's generic gray shield with its own branded "Peak Focus"
  block screen — exactly what Opal does. Without it you still block, but the screen is
  Apple's default.

### 3.4 `ShieldAction` extension (REQUIRED for interventions)
- Subclass `ShieldActionDelegate`; override
  `handle(action:for:completionHandler:)` and return `.none`, `.close`, or `.defer`.
- Powers "unlock for 5 minutes" / "go back" by mutating the `ManagedSettingsStore` from
  inside the extension.

### 3.5 `DeviceActivityReport` extension (REQUIRED for usage stats)
- A `DeviceActivityReportExtension` with SwiftUI scenes
  (`DeviceActivityReportScene` + a `DeviceActivityReport.Context`).
- **This is the ONLY supported way to show per-app/website usage data and localized app
  names.** The host app embeds a `DeviceActivityReport` view that renders this extension's
  scene; the underlying data never crosses into the host process. If Flint wants Opal-style
  "you spent 2h on Instagram" charts, this extension is mandatory — there is no other path.

### 3.6 App Group (shared container, REQUIRED)
- All targets join one App Group (e.g. `group.com.flint.peakfocus`).
- Store the `Codable` `FamilyActivitySelection` and a **token → metadata lookup table**
  here so the extensions can read the user's choices and best-guess block context. Named
  `ManagedSettingsStore`s are auto-shared between app and extensions on iOS 16+, but the
  selection blob and any app-defined state still need the App Group.

> **Network filtering note:** A `NEFilterDataProvider` (Network Extension) content filter,
> which would let Flint block at the network layer instead of only at app launch, is
> **available only under `.child` authorization or MDM** — not `.individual`. Flint's
> individual-mode product therefore cannot ship a network filter. Shielding is launch/
> foreground-time only. (See §6.)

---

## 4. The entitlement reality for an open-source / free app

This is the make-or-break distribution fact, and the verifier confirms it explicitly.

- **`com.apple.developer.family-controls` is free to add for development** in Xcode's
  Signing & Capabilities. You can build, run on device, and develop the entire app today
  without asking Apple for anything.
- **App Store distribution is gated.** A distribution/deployment build **cannot be
  uploaded to App Store Connect** until Apple grants the *distribution* entitlement. This
  is a separate approval, not the dev capability.
- **How an indie / open-source dev obtains it:** submit Apple's request form at
  `https://developer.apple.com/contact/request/family-controls-distribution`, supplying:
  - developer / contact details,
  - a **website URL** — **a GitHub repo page has been accepted** (good news for Flint),
  - the App Store Connect Apple ID,
  - a use-case description of how the app uses the entitlement.
- **Approval is per-Bundle-ID.** Every Screen Time extension
  (`DeviceActivityMonitor`, `DeviceActivityReport`, `ShieldConfiguration`, `ShieldAction`)
  needs its **own** request under the same Apple ID. Budget one request per target.
- **Turnaround (reported):** roughly **3–4 business days to a few weeks** for the first
  request, often **≤ 1 day** for subsequent extension requests under the same account.
  Some 2026 reports note multi-week delays and portal/provisioning-profile glitches — so
  **submit early, well before any launch date.**
- **Free is not disqualifying.** Self-control / digital-wellbeing blockers used by adults
  on their own device are an **explicitly supported individual-authorization use case**,
  and Apple positions `.individual` for exactly this. There is **no evidence that free or
  open-source apps are rejected on price**; eligibility turns on a legitimate use case and
  a complete App Store Connect listing.

**Open-source distribution caveat to document for contributors:** because approval is tied
to a specific Apple ID and set of Bundle IDs, anyone who forks Flint and wants to ship
their own build to the App Store must (a) use their own Bundle IDs, and (b) file their own
entitlement requests. The source is fully open; the *shipping signing identity* is not
transferable. Side-loading / personal-team builds work for development without the
distribution entitlement, subject to the usual 7-day personal-team provisioning limits.

---

## 5. Minimum OS version

**Target iOS / iPadOS 16.0 as the floor.** Rationale:

- `.individual` authorization — the entire premise of an adult self-blocker that is *not*
  parental controls — **requires iOS 16.0**. On iOS 15 only `.child` (parent-approved via
  iCloud Family) exists, which is the wrong product.
- `ManagedSettingsUI` (`ShieldConfiguration`, `ShieldAction`) — branded shields and
  intervention buttons — is **iOS 16.0**.
- Web-domain shielding, named-store auto-sharing, and the matured `DeviceActivityReport`
  extension are all iOS 16.0.

Supporting iOS 15 would buy nothing for an individual-mode blocker (no `.individual`, no
custom shield UI) and add real complexity. **iOS 16.0 minimum.** Consider iOS 17.0 if the
team wants to drop early-16 quirks, but 16.0 is the defensible floor. (Mac Catalyst is out
of scope — `ManagedSettings` is not available on macOS.)

---

## 6. What's possible vs. impossible

### Possible (Flint can match Opal here)
- Block any number of user-selected **apps, app categories, web domains, and web
  categories** via shields (subject to the 50-token cap, §6, "Hard limits").
- **Scheduled blocking** (recurring focus windows) via `DeviceActivitySchedule`.
- **Usage-threshold blocking** ("block after 30 min of TikTok") via `DeviceActivityEvent`
  + `eventDidReachThreshold`.
- **Branded block screen** with custom copy, colors, icon, and two buttons.
- **Timed unblock / "break"** interventions via the `ShieldAction` extension.
- **Usage statistics UI** (per-app time, localized names, charts) via the
  `DeviceActivityReport` extension.
- All of the above **with the app closed** — the OS drives the extensions.

### Impossible / out of bounds (these are Apple's limits, not Flint's)
- **Reading what the user blocked.** Tokens are opaque: no bundle ID, no name, no icon in
  the host app. You can only render a token via a system `Label`, and reliably only inside
  the picker or the extensions — not freely in arbitrary host UI.
- **Building a block list programmatically.** You cannot construct a `FamilyActivitySelection`
  from bundle IDs or remotely push a block list. **Only the user, through the picker, can
  choose.** This is intentional (anti-abuse) and means no "block these 50 apps" presets
  shipped as data — presets must be re-selected by the user via the picker.
- **Launching the blocked app, or returning to Flint, from the shield.** No API returns a
  launch URL from a token (**FB15500695**), and there is no API to open the parent app from
  the shield (**FB15079668**). Apps fall back to local push notifications, which users find
  confusing. Plan the intervention UX around this gap.
- **Network/VPN-level filtering** under `.individual`. Shielding only blocks at app
  launch/foreground. A `NEFilterDataProvider` network filter needs `.child` auth or MDM.
- **Shielding core system apps** (Messages, Phone, Settings). Attempts are silently ignored.
- **Passcode-locking Flint's own Screen Time access.** A user can revoke a third-party
  app's Screen Time permission with a single toggle in *Settings → Screen Time*, and there
  is **no API to protect that toggle** (**FB18794535**). This — plus the fact that
  `.individual` does **not** impose the parental implicit restrictions, so the user can
  also just delete Flint — is the category's fundamental "just turn it off / delete it"
  weakness. Competitors work around it with the **system** Screen Time passcode + iOS
  Shortcuts automations (user-driven setup); Flint can document the same, but it cannot be
  enforced by API.

### Hard limits to engineer around
- **~50 tokens per shield type, and it fails SILENTLY.** Assigning more than ~50 tokens to
  `store.shield.applications` (or `.webDomains`) makes the property return `nil` and shield
  **nothing**, with **no error thrown**. The cap is effectively **combined/system-wide
  across all stores** — multiple named stores do **not** multiply it (confirmed still
  present through iOS 17.2). **Mitigations:**
  - Use **category tokens** for large selections (cost: per-app granularity).
  - Validate selection size in the app and warn the user before they exceed the cap.
  - Treat 50 as a real product constraint, not a soft suggestion.
- **iOS 16 allows up to 50 named `ManagedSettingsStore`s per process** — useful for
  separating concerns (e.g. one store per active session) but does **not** raise the token
  cap.
- **Tokens rotate.** iOS regenerates/rotates `ApplicationToken`s over time (**FB14082790**).
  Persisted selections can go stale; re-validate, and never assume a stored token still
  resolves. Reference selections by a **stored ID**, not by re-passing the raw
  (potentially huge, for `includeEntireCategory`) encoded blob.
- **Extensions can't tell which context a token was shielded for** (**FB14082790 /
  FB14237883**). The `ShieldConfigurationDataSource` is handed only a token. Keep a shared
  App Group **lookup table** (token → which session/schedule/threshold) and best-guess;
  stale configs can be reused when a token migrates between stores.
- **Extension sandbox:** short-lived, memory-constrained, no network in the Report
  extension, limited IPC. Keep extension code minimal and stateless beyond the App Group.

---

## 7. Recommended target & module layout for `ios-app/`

Greenfield layout (the `ios-app/` directory does not yet exist). Designed so the shared
core (tokens, App Group I/O, models) is a Swift Package consumed by **both** the app and
**every** extension — extensions cannot import the app target, so shared logic must live in
a package.

```
ios-app/
├── Flint.xcodeproj
├── Flint/                                  # Host app target  (bundle: com.flint.peakfocus)
│   ├── FlintApp.swift
│   ├── Features/
│   │   ├── Authorization/                  # AuthorizationCenter flow, status UI
│   │   ├── Onboarding/
│   │   ├── BlockList/                      # FamilyActivityPicker presentation
│   │   ├── Sessions/                       # focus sessions, start/stop monitoring
│   │   ├── Schedules/                      # recurring DeviceActivitySchedule UI
│   │   ├── Limits/                         # usage-threshold (DeviceActivityEvent) UI
│   │   └── Stats/                          # embeds DeviceActivityReport view
│   ├── Resources/                          # Assets, brand colors (#EF9F27 / #BA7517 / #2C2C2A)
│   └── Flint.entitlements                  # family-controls + App Group
│
├── Extensions/
│   ├── DeviceActivityMonitorExtension/     # com.flint.peakfocus.monitor
│   │   ├── FlintMonitor.swift              # applies/clears shields on boundaries
│   │   └── *.entitlements
│   ├── ShieldConfigurationExtension/       # com.flint.peakfocus.shield-config
│   │   ├── FlintShieldConfiguration.swift  # branded "Peak Focus" block screen
│   │   └── *.entitlements
│   ├── ShieldActionExtension/              # com.flint.peakfocus.shield-action
│   │   ├── FlintShieldAction.swift         # unlock-for-N-min / go-back
│   │   └── *.entitlements
│   └── DeviceActivityReportExtension/      # com.flint.peakfocus.report
│       ├── FlintReportScene.swift          # usage charts + localized names
│       └── *.entitlements
│
└── Packages/
    └── FlintCore/                          # Swift Package — shared by app AND all extensions
        └── Sources/
            ├── FlintModel/                 # Session, Schedule, Limit, Codable selection wrappers
            ├── FlintShieldStore/           # ManagedSettingsStore wrappers, the ~50-token guard
            ├── FlintGroupStore/            # App Group I/O: selection blob + token→context lookup
            ├── FlintTokens/                # opaque-token helpers, ID-based references, rotation handling
            └── FlintScheduling/            # DeviceActivityCenter / Schedule / Event builders
```

**Layout principles:**
- **`FlintCore` is the spine.** Extensions are tiny and stateless; everything reusable
  (shield writing, App Group reads, the 50-token guard, token-rotation re-validation, the
  token→context lookup table) lives in the package so the app and all four extensions share
  one implementation. Extensions must never depend on the app target.
- **One target = one Bundle ID = one entitlement request.** The four extension folders map
  one-to-one to the four per-Bundle-ID `family-controls` distribution requests in §4. Name
  Bundle IDs predictably (`com.flint.peakfocus.<role>`) so the requests are easy to track.
- **Every target joins the same App Group** (`group.com.flint.peakfocus`) and declares the
  `family-controls` entitlement (dev auto; distribution per §4).
- **Shields are written from `DeviceActivityMonitorExtension`, not the app**, so blocking
  survives the app being closed or killed.
- **Stats require the Report extension** — it is the only legal source of per-app usage and
  localized names; budget it as a first-class target, not an afterthought.

---

## 8. Build-order recommendation

1. **Authorization spike** — `.individual` request flow + status handling. Verifies the
   dev entitlement works on a real device.
2. **Picker → selection → App Group** persistence in `FlintCore`.
3. **`DeviceActivityMonitor` + `ManagedSettingsStore`** — apply/clear a shield manually.
   First moment something is actually blocked.
4. **Scheduling + thresholds** via `DeviceActivityCenter`.
5. **`ShieldConfiguration`** — brand the block screen.
6. **`ShieldAction`** — timed-unblock intervention.
7. **`DeviceActivityReport`** — usage stats.
8. **File all distribution entitlement requests early** (one per Bundle ID); they gate the
   first TestFlight/App Store build and can take weeks.

---

## 9. Open feedback IDs to track (Apple bugs that constrain Flint)

| Feedback | Impact on Flint |
| --- | --- |
| **FB18794535** | No API to passcode-lock third-party Screen Time access → one-toggle bypass. |
| **FB14082790** | App tokens rotate / regenerate; persisted selections go stale. |
| **FB14237883** | Extensions can't tell which block context a token belongs to. |
| **FB15500695** | No launch URL from a token; can't open the blocked app. |
| **FB15079668** | No API to return to the parent app from the shield. |

These are upstream Apple limitations shared by every competitor including Opal. Flint should
document them honestly to users and design UX (notifications-as-fallback, App-Group context
lookup, size guards) around them rather than promising what the API cannot deliver.
