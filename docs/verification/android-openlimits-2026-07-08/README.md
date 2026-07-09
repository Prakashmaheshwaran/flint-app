# Android Open Limits emulator verification — 2026-07-08

Environment:
- AVD: `flint-test`
- Device reported by adb: `sdk_gphone64_arm64`, API 35, google_apis, arm64 (fingerprint
  `google/sdk_gphone64_arm64/emu64a:15/AE3A.240806.043/12960925:userdebug/dev-keys`)
- Detection path under test: Path B fallback (`UsageStatsForegroundService` 1 s poll +
  `SYSTEM_ALERT_WINDOW` overlay), with `FlintAccessibilityService` forced off.
- Permissions: `android:get_usage_stats` allow, `SYSTEM_ALERT_WINDOW` allow,
  `POST_NOTIFICATIONS` granted; `pm clear com.flint.peakfocus` before each run.
- Source under test: `main` @ `1bf69e6` **plus the then-uncommitted working tree** (the
  82-file premium-UI / Block Now branch). APK:
  `android-app/app/build/outputs/apk/debug/app-debug.apk`, sha256
  `611691910512fcdeb8f3567d29f392ecb3929c0019d128d478b445ed83d5c6c3`, built 2026-07-08
  02:04 from that tree.

Commands run (2026-07-08, runs at 07:03Z and 07:06Z; the two driver scripts are archived
in this folder — they follow `scripts/android-pathb-verify.sh` conventions but ran from a
session scratch directory, not from `scripts/`):

```bash
make android-test     # green (unit tests: engine, policies, codecs, drafts)
make android          # green (assembles the debug APK)
bash drive.sh         # main verification — installs APK, pm clears, drives the UI, asserts
bash measure.sh       # timing + persistence follow-up (fresh pm clear, re-authored via UI)
```

Result: all four passed. `drive.sh` and `measure.sh` exited 0 with every assertion green;
full transcripts in `evidence/run.txt` and `evidence-measure/run.txt`.

Why the run drives the real UI: the debug `SetBlocklistReceiver` can seed the legacy
blocklist, schedules, and daily **Time** Limits, but has **no Open-Limit hook** — Open
Limits are written only by the Limits editor into the DataStore `LimitsStore`. So the
script authors the limit through the actual UI, which is a feature, not a workaround: it
verifies the whole chain **Blocklist tab → New app limit → app picker → limit editor →
DataStore → `BlockScreenCoordinator` (openLimits snapshot) → `OpenLimitPolicy` → shield**.

Scenario: **Clock (`com.google.android.deskclock`) · 2 opens/day · EASY**, enforced by the
Path B 1 s poll; the shield is the `TYPE_APPLICATION_OVERLAY` window (asserted via
`dumpsys window windows`).

Covered (each line asserted by `drive.sh`):
- `UsageStatsForegroundService` running as a foreground service with AccessibilityService
  off (`evidence/00-pathb-service.txt`).
- Open Limit authored through the UI; the Blocklist overview row shows
  **"2 opens/day (Easy)"** (`evidence/01`–`05-*.png`; `04-opens-entered.png` shows the
  editor with the toggle on, opens = 2, Easy).
- **Open #1 allowed** — Clock stayed unshielded for ~5 s ≈ 5 poll ticks
  (`evidence/06-open1-allowed.png` + `-windows.txt`).
- **Open #2 hits the quota** — branded shield over Clock: `OPEN LIMIT` badge, "You've used
  today's opens for this app.", "Clock can wait", "Unblocks in 20h 55m" (next local
  midnight) (`evidence/07-open2-blocked.png`, `07-open2-blocked-ui.xml`, `-windows.txt`).
- Leaving the app (HOME) stands the shield down (`evidence/08-home-standdown.png`).
- An over-quota reopen **re-shields** (`evidence/09-open3-reshield.png`).
- The EASY **"Take a break"** action on the open-limit shield grants the 5-minute
  exemption and drops the shield — the "use anyway" affordance, spending one more recorded
  open per `BlockCause.spendsOpenOnBreak` (`evidence/10-break-standdown.png`).

Measurements (`measure.sh`, fresh `pm clear`, limit re-authored through the UI):
- Open #1 time-to-shield: **none** within 8 s (expected — the open is within quota).
- Open #2 time-to-shield after launch: **1.83 s** (see the finding below).
- Reopen after `am force-stop` + relaunch: shield up in **0.77 s** with the cause still
  `OPEN LIMIT` — open counts persist across process death (`FocusStateStore`), and
  `PathBServiceGate` re-armed the foreground service on resume
  (`evidence-measure/m2-service-after-forcestop.txt`).

## Finding — the last allowed open is truncated after ~1.8 s (FIXED 2026-07-09)

"Opens per day: 2" in practice gives **one full open plus one ~2-second open** on Path B.
Cause: `BlockScreenCoordinator.onForegroundChanged` re-decides on **every** 1 s poll tick
and `OpenLimitPolicy.decide` blocks at `used >= dailyOpens` — the entry tick of open #2
records `used = 2`, and the next tick shields even though the user is *inside* the open
that was just granted. The engine unit test `atQuotaBlocksAndCarriesTheLimit` deliberately
asserts at-quota blocking, and the model KDoc says "After the quota, the block screen
appears" — so this may be intended; but a user reading "2 opens per day" plausibly expects
two *usable* opens. Path A shares the same coordinator/policy and would behave the same on
its event-driven cadence, just less deterministically than the 1 s poll. If the behavior
is unwanted, the fix belongs in the coordinator/policy seam (e.g. only surface `Blocked`
on transitions that `countsAsOpen`, treating the in-progress granted open as exempt until
the user leaves) — minding re-entry semantics after breaks.

### Resolution (2026-07-09)

The behavior was unwanted: "N opens per day" now means N *usable* opens. The fix landed at
the seam this finding predicted — `OpenLimitPolicy.onForegroundTransition` decides the quota
once, at entry, and carries the granted open across re-evaluations until a different real app
takes the foreground (shade dips and Flint's own windows do not end it). Re-entering an
exhausted app still blocks, and a "use anyway" break still re-blocks when its exemption ends.
Covered by `OpenLimitPolicyTest`'s foreground-transition cases.

**Everything above and below this section describes the pre-fix build** (APK sha256
`6116919…`, built 2026-07-08) and is left intact as the dated record. Two consequences for
anyone re-running the archived scripts against a current build:

- `measure.sh`'s open-#2 assertion (`[[ "$t2" != "none" ]]`) encodes the *old* behavior and is
  now expected to fail — post-fix, open #2 should measure `none` within its 10 s window.
- `drive.sh`'s "open #2 hits the quota" step now needs a *third* launch to reach the shield;
  its `07-open2-blocked` step will not shield on the second open.

Both scripts are left as archived, so they still reproduce the run whose transcripts and
screenshots sit beside them. A fresh emulator pass against the fixed build has not been run.

Also noted: the shield badge text is uppercased by the component (`BlockScreenState` maps
the reason to "Open limit", `FlintBadge` renders `.uppercase()`), so scripted assertions
must grep `OPEN LIMIT` — the same rule the Path B run's `TIME LIMIT` grep followed.

Evidence:
- `drive.sh` / `measure.sh` — the archived driver scripts (reproduce: `make android-test
  && make android`, then run each against a booted `flint-test` AVD).
- `evidence/` — the full clean pass (07:03Z):
  - `run.txt` — full command/assertion transcript (PASS 0–6).
  - `00-pathb-service.txt` — service dump proving foreground-service state, a11y off.
  - `01-blocklist-tab.png` · `02-app-picker.png` · `03-limit-editor.png` ·
    `04-opens-entered.png` · `05-limit-saved-overview.png` — UI authoring steps.
  - `06-open1-allowed.png` / `06-open1-allowed-windows.txt` — first open unshielded.
  - `07-open2-blocked.png` / `07-open2-blocked-ui.xml` /
    `07-open2-blocked-windows.txt` — at-quota `OPEN LIMIT` shield content + overlay window.
  - `08-home-standdown.png` / `08-home-standdown-windows.txt` — stand-down on HOME.
  - `09-open3-reshield.png` / `09-open3-reshield-windows.txt` — over-quota re-shield.
  - `10-break-standdown.png` / `10-break-standdown-windows.txt` — EASY break stand-down.
  - `99-final.png` / `99-final-windows.txt` / `99-logcat-flint.txt` / `ui.xml` — end
    state, filtered logcat, final UI dump.
- `evidence-measure/` — the timing/persistence pass (07:06Z):
  - `run.txt` — measurement transcript (open1 = none, open2 = 1.83 s,
    post-force-stop = 0.77 s).
  - `m1-open2-shielded.png` — the shield at the 1.83 s mark.
  - `m2-reshield-after-forcestop.png` / `m2-service-after-forcestop.txt` — persistence +
    service re-arm after `am force-stop`.
  - `ui.xml` — final UI dump.

Run history (transparency): two earlier partial runs failed on script-harness bugs, not
app behavior — run 1 waited for a button that sits below the fold after Save (fixed with a
scroll-find); run 2 grepped `Open limit` where the badge renders `OPEN LIMIT`. The app
behaved correctly in all three runs; the partial-run artifacts stayed in the session
workspace and are summarized here rather than committed.

Not covered by this run:
- Path A open counting — AccessibilityService was deliberately off; Path A shares the
  coordinator/policy but has event-driven cadence. Emulator-verifiable next.
- HARDER/HARDCORE open-limit tiers (only EASY exercised), the *observable* effect of the
  break's extra spent open (needs the 5-minute exemption to lapse), real-midnight
  rollover, and the "system-surface bounces don't count as opens" heuristic (JVM-tested
  only).
- Sleep windows, boot re-arm, OEM kill behavior, and real-device resilience — device-only
  proof stays in the board's `H-*` tasks.
