# Flint — Android

Native Kotlin/Jetpack Compose focus app. Minimum API 23, compile/target API 36.
The [v1 acceptance ledger](../docs/acceptance/v1-capability-ledger.md) is the current
capability/evidence record; stock/OEM hardware acceptance is pending.

## Build and verify

```sh
# repository root; JDK 21 and Android SDK required
make env
make android
make verify-android
make android-release
make android-bundle
```

`scripts/flint-env.sh` resolves SDK/JDK tools; existing environment wins.
[Signing](../docs/store/android/signing.md) is owner-controlled. Without private upload-key
configuration, release artifacts are explicitly development-signed previews.
[Bundle/exclusion checks](../docs/store/android/bundles.md) inspect the actual APK/AAB,
including no release Internet/network-state permission or connected/Firebase account code.

## Enforcement paths and safety

Path A uses AccessibilityService foreground events and known-browser address-bar adapters.
Path B uses UsageStats plus the specialUse foreground service, with overlay/Activity
handoff; it has **app-only**, not equivalent domain coverage. Actual Accessibility binding,
rather than a permission bit alone, determines failover. The safety floor applies before
every cause: emergency/calling, default dialer and Home, system/permission recovery,
the uninstaller and Flint remain reachable.

Android no longer intercepts Settings, uninstall, Accessibility disable, force-stop or
permission revocation at any tier. No pass, readable Settings text or special exception
is required for those OS controls. Enabling/re-enabling Accessibility goes through the
same affirmative prominent disclosure; declining leaves the alternate setup path available.
Flint does not claim guardian/device-owner authority or Play approval.

Merged continuous-evaluation work retains quiet foreground observations, reevaluates at
schedule/expiry/budget boundaries and recovers detector handoff. Positive budgets require
measured Usage Access; zero budgets are immediately exhausted. The
[local emulator evidence](../docs/verification/android-continuous-eval-2026-10-28/README.md)
records separate Path A/B schedule and growing-budget observations. Path B budget's
first-visible-window upper bound was +20.608 s, and a cold-start ANR under heavy load remains
an observed unresolved limit. These observations do not establish low-end/OEM resilience.

Same-boot measured clock discrepancies cannot be treated as a clock-edit veto. Durable
grant receipts bind access to the boot and preserve consumption; process restart and reboot
are distinct. Persisted quota-zone/reset and the full ADR-012 migration/runtime policy must
be checked in the ledger. Unmeasured clock changes across reboot remain uncertain.

## Sessions, rules and local outcomes

The Today / Rules / Insights / You shell includes finite Timers, app block/allow rules,
weekday schedules, Time/Open Limits, Sleep/Morning Assist, named app groups and five routine
presets. [ADR-012](../docs/architecture/protection-contract.md) defines the intended shared
tier and pass rights; the ledger records which live routes implement them.

Merged [#139](https://github.com/Prakashmaheshwaran/flint-app/pull/139) adds durable
editor confirmations, occurrence-scoped Harder waits, future-only Hardcore weakening,
per-Time-Limit tiers and new-versus-legacy Timer exit rights. Its source/tests do not
prove every enforcement runtime caller consumes that policy. Rolling/global pass recovery
and explicit five-minute Open-credit access still need separate live integration evidence.
Legacy Timer hold-to-end evidence remains dated; the ledger keeps the two contracts distinct.
Arbitrary future date-specific schedules, repeated Pomodoro chains, Timer Allow List/sites,
Android automation, browser private-mode/history lockdown and app-open PIN are absent at
this checkpoint.

History is local and independent of UsageStats. Today/Complete/Insights derive focus time,
outcomes and streaks from Timer events; usage rankings stay separate. A
[hosted real five-minute Timer run](../docs/verification/f-android-history-2026-10-28/README.md)
proves completion/early-end persistence and presentation with blocking off. It does not prove
all measured intercepted-open/break/pass/coverage producers or history-clear UI.

Optional planned-focus-end reminders use an inexact alarm and the `focus_sessions`
**Focus end reminders** channel. Read-only Glance widgets and optional Path A ongoing status
use public status/history readers, with stale/unavailable fallback; current producer/service
wiring and actual launcher/notification delivery must be evidenced separately. Path A uses
`flint_ongoing_session` / **Focus in progress**; mandatory Path B FGS uses
`flint_enforcement`. Notification denial affects visibility, not authority to block.

You includes native offline Privacy Policy and open-source license screens. The release
policy is [docs/privacy-policy.md](../docs/privacy-policy.md); the generated notices come
from actual release dependencies and fonts. No network WebView is required.

## Verification boundaries and release

[Hosted Android E2E](../docs/verification/android-e2e.md) covers consent, UI-authored rule
persistence, real Accessibility shield/Easy release, a legacy pass and an exhausted budget.
A separate installed-release smoke exercises the minified release-code APK. These do not
certify reboot/Doze/OEM survival, full break/credit/pass expiry, web coverage, TalkBack/Switch
Access, current-device safety or store approval. The
[consolidated hardware plan](../docs/acceptance/device-acceptance-plan.md) orders those checks.

AAB/release-code CI, target API 36, release exclusions and store drafts are implemented;
owner key custody, Play App Signing, Play-delivered update/data preservation, declarations,
public support/privacy URLs, real screenshots/video and submission remain
[human-owned](../docs/store/README.md). App backup is disabled; uninstall can erase local data.

The optional connected account/group/managed-authoring client and Firebase account SDK exist
only in debug and talk to a local test service. Managed enforcement, production accounts,
cloud sync, billing, analytics and personal usage uploads are not activated by this work.
The [older parity investigation](../docs/research/06-opal-android-parity.md) retains its
historical expanded scope and is superseded by the v1 acceptance ledger for release claims.
