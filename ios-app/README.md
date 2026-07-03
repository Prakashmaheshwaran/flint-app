# Flint — iOS

Native iOS/iPadOS app (Swift + SwiftUI) built entirely on Apple's **Screen Time API** — the
only sanctioned way a third party can block apps/websites. Strategy & rationale:
[`../docs/research/02-ios-technical-strategy.md`](../docs/research/02-ios-technical-strategy.md).

## Generate & build

The `.xcodeproj` is **generated** from [`project.yml`](project.yml) with
[XcodeGen](https://github.com/yonghuang-hub/XcodeGen) — never hand-edited, never committed.

```bash
# from repo root
brew install xcodegen     # one time
make ios                  # generate Flint.xcodeproj and open it
# or:
make ios-gen              # just (re)generate the project
```

Then in Xcode: select the **Flint** scheme, set your **Development Team** on each target
(Signing & Capabilities), and run on a real device or simulator (iOS 16+).

> **Requires full Xcode** (not just Command Line Tools) to build. XcodeGen only needs to
> generate the project.

## Targets (5)

| Target | Bundle ID | Role |
|---|---|---|
| `Flint` | `com.flint.peakfocus` | SwiftUI host app: authorization, app picker, sessions/limits UI, embeds the stats report |
| `DeviceActivityMonitorExtension` | `…​.monitor` | Applies/clears shields on schedule + threshold boundaries (so blocking survives app death) |
| `ShieldConfigurationExtension` | `…​.shield-config` | The branded "Peak Focus" block screen |
| `ShieldActionExtension` | `…​.shield-action` | Block-screen button handling |
| `DeviceActivityReportExtension` | `…​.report` | Usage-stats UI — the **only** legal source of per-app usage + localized names |

All five share the **`group.com.flint.peakfocus`** App Group and the
`com.apple.developer.family-controls` entitlement. Shared logic lives in the **`FlintCore`**
Swift package (`Packages/FlintCore`) so the app and every extension reuse one implementation
(extensions can't import the app target).

## FlintCore (the spine)

| File | What it holds |
|---|---|
| `FlintBrand` | Brand palette as SwiftUI `Color` + `UIColor` (for the shield) |
| `FlintModel` | `FlintSession`, `FlintSchedule`, `BreakLevel` (incl. free `hardcore`) |
| `FlintGroupStore` | App Group I/O: the `FamilyActivitySelection` blob + sessions |
| `FlintShieldStore` | `ManagedSettingsStore` wrapper + the silent ~50-token cap guard |
| `FlintScheduling` | `DeviceActivityCenter` / schedule / event builders |
| `FlintTokens` | Opaque/rotating-token helpers, shield-cap checks |

Tests in `Packages/FlintCore/Tests` (run via the Xcode test action on a simulator).

## The entitlement reality (read before planning a release)

- `com.apple.developer.family-controls` is **free to develop** with — build & run on device today.
- **App Store distribution is gated**: Apple must grant the *distribution* entitlement per
  Bundle ID (all 5), via the request form. A GitHub repo URL is accepted as the "website";
  free/open-source apps are **not** disqualified. Turnaround: days → weeks. **File early.**
- Forkers shipping their own build must use their **own** bundle IDs and file their **own**
  entitlement requests (the signing identity is not transferable).

## Verify

```bash
make ios-build      # compile app + 4 extensions (BUILD SUCCEEDED)
# unit tests on a booted simulator:
DEVELOPER_DIR="$(ls -d /Applications/Xcode*.app | tail -1)/Contents/Developer" \
  xcodebuild test -project ios-app/Flint.xcodeproj -scheme Flint \
  -destination 'platform=iOS Simulator,name=iPhone 17' CODE_SIGNING_ALLOWED=NO
```

> **Simulator vs. device:** the app *runs* in the Simulator (UI, navigation, persistence), but
> Screen Time **authorization** and **shield enforcement** only function on a real device — the
> Simulator can't grant Family Controls or apply shields. So "the block actually blocks" is
> verified on device; everything else (logic, UI, build) is verified in the Simulator + tests.

## Status

**Done & verified:** all 5 targets build (`make ios-build` — the fleet merge gate re-runs it as
each change lands); app runs in the Simulator; `FlintCore` unit tests pass. On-device enforcement
proof is a separate, human task (`H-IOS-DEVICE`) — nothing below claims it.
Verticals implemented:
- **Block Now** — authorize → pick apps → timed shield + `DeviceActivity` auto-clear → live
  countdown → stop gated by break level (incl. **free Hardcore**).
- **Schedules** — unlimited recurring/daily rules (**no count cap, no 24h-advance cap**). Each
  rule carries its own selection + break level + day-of-week gate + allow-list, and gets its own
  `DeviceActivity` registration + `ManagedSettingsStore` (so schedules can overlap cleanly).
- **Break levels + Emergency Pass** — Easy/Harder/**free Hardcore** everywhere; a free **weekly
  Emergency Pass** ends a running Hardcore session early (Opal paywalls this).
- **Time Limits** — daily usage budgets via `DeviceActivityEvent` thresholds; the monitor shields
  the apps when the budget is hit and clears at the day boundary (free, incl. the hard-reset tier).
- **Website blocking + usage report** — web-domain shields apply through the same picker/monitor
  path; the **Stats** tab embeds the `DeviceActivityReport` extension's "Total Activity" scene
  (real per-app usage on device; empty in the Simulator).
- **App groups / presets + Allow List** — save a picker selection as a named, reusable group and
  apply it in one tap; Allow List ("brick phone") ships in Schedules (`.all(except:)`).
- **App-PIN anti-bypass** — optional app-open PIN (salted SHA-256, never stored plaintext) gates
  Flint; the **Settings** tab surfaces the system Screen Time passcode as the real anti-uninstall
  guarantee.
- **Safari restrictions + Private-Browsing lockdown** — Settings → *Web & Safari*: while a
  session runs, Safari follows either the adult-content filter (plus custom block/allow domains)
  or an allowed-sites-only list, with an optional explicit-media toggle. iOS ties Private
  Browsing and *Clear History* to web restrictions, so both lock down too. Applies on a real
  device only — the Simulator can't set web restrictions.
- **Focus Filter** — a `SetFocusFilterIntent` attaches Flint to any system Focus (Work, Sleep, …):
  blocking starts when the Focus turns on and stops when it turns off. Per-Focus preset +
  strictness are chosen in iOS Settings → Focus; Flint's Settings → *Automation → Focus Filter*
  screen sets the defaults new filters pre-fill from. Focus filters only fire on a real device —
  build-verified, **not** yet device-verified.
- **Siri & Shortcuts** — App Intents (*Start Session*, *Start Pomodoro*, *Stop Session*) with
  Siri phrases; iOS auto-discovers them from the app binary (no extra extension target), and
  saved app groups surface as a preset parameter. The sessions they start enforce on a real
  device only, like everything shield-based.
- **Sleep Mode + Morning Assist** — Settings → *Sleep*: a bedtime → wake window on chosen
  nights, Sleep Assist strictness (Off / Wind Down / **Full Assist — free**), an optional
  enforced morning wind-up, and one saved app group allowed through overnight. It materializes
  into two rules on the Schedules engine ("Sleep Mode" / "Morning Assist"), so enforcement rides
  the same monitor path as Schedules. Blocking-only by design (no soundscapes/sleep stories);
  Slow Uplift stores the choice but arms no shield — the screen says so. Device-gated like all
  shields.
- **Open Limits (launch-count caps)** — Screen Time has no launch-count event, so Flint counts
  *intentional* opens at the shield: the apps stay shielded and the block screen's action button
  spends one of the day's allowed opens. `FlintOpenLimitEnforcer` matches the tapped token back
  to its saved rule (extensions can't tell an open-limit token from a hard-block one —
  FB14237883), releases the token on a grant, keeps the shield once the cap is spent, never
  charges an open that another layer (Session/Schedule/Time Limit) would still block, and fails
  **closed** if the App Group is unreadable. Unit-tested; wired into `ShieldActionExtension`.
  The user-facing end is implemented too: a config screen (Limits tab → *Open Limits*:
  create/edit/toggle rules — opens-per-day, app/site picker, break level), host-app arming via
  `FlintOpenLimitsController` (on launch and rule edits), a day-boundary re-arm through the
  monitor extension (each rule registers an all-day activity), and the "Use app (N left)" /
  opens-spent labels on the block screen. **Compile verification of the config-UI + arming
  layer is pending on the macOS CI toolchain** (it was written without a local Xcode), and
  grants enforce on a real device only, like every shield.

**Next:**
1. **On-device validation** of everything enforcement-shaped — shields, schedules, time limits,
   open-limit grants, web restrictions, Focus filters, sleep windows. The Simulator cannot prove
   any of it; tracked as `H-IOS-DEVICE` (human + hardware), evidence goes in `docs/verification/`.
2. **macOS compile pass over the Open-Limits config UI + arming** — that layer closes the last
   product gap on paper but was built toolchain-blind; `xcodegen generate` + `xcodebuild`
   build/test must go green before the blanket "all targets build" claim covers it.

Build order: strategy doc §8.
