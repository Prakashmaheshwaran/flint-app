# Timer creation: additional APK evidence

Reference: Opal Android 4.14.0 / 4014000; base APK SHA-256
`9b4348953e5da3e6a01e87ff3e76e9e059f5d3909e69a95b4d29c3d56b10710a`.
All Java paths below are relative to `android-app/build/opal-analysis/decompiled/sources/`.
This is static evidence; no authenticated Timer screen was captured.

## Recovering the failed coroutine

JADX could not decompile `d1/z3.java`'s `invokeSuspend`. Android SDK `apkanalyzer dex code`
successfully disassembled it. Switch case 3 (`:pswitch_157`) reads the Timer state and calls:

```text
tk.s.x(durationMinutes, selectionMode, selectedApps, selectedDomains, detoxPresetId, continuation)
```

The coroutine waits for this call before emitting the active-screen event, distinguishes
ordinary and Detox routes, catches an exception into the error snackbar, and clears the
in-flight flag on success and failure. The disassembly is retained locally as
`android-app/build/opal-analysis/timer-start.smali`; no authentication was bypassed.

## Persisted session semantics

`tk/s.java:1326–1399` is successfully decompiled. A second DEX disassembly,
`android-app/build/opal-analysis/timer-create.smali`, corroborates its duration branches.

| Property | Recovered behavior |
|---|---|
| Identity | Random UUID; blocking rule ID is prefixed `timer-session-` |
| Start | Injected `Clock.millis()` |
| Finite duration | End is start + duration minutes × 60,000 |
| Infinity | Duration 0 creates an open-ended interval with a null end |
| Targets | Explicit app package names and domains, with block/allow selection mode |
| Rule | Enabled, named Focus Timer, with `Strict` enforcement (`zp/a1.java`) |
| Metadata | Session ID, rule ID, duration, start, optional Detox preset ID |
| Writes | `tr.j.E` transaction wrapper invokes `d1.p6` case 2: rule repository write, then session metadata write |

`h20/d.java:36–46` independently confirms that duration 0 has no countdown deadline;
finite remaining seconds clamp to zero. This closes the earlier uncertainty about
infinite-duration semantics. Transaction rollback and runtime failure recovery have
not been exercised in the reference app.

## Controls and remaining differences

- `j20/i0.java`: infinity; 5–120 minutes in steps of 5; then 3–24 hours.
- `j20/f0.java`: Deep Study 90m, Commute 30m, Workout 60m, Reading 30m, Weekend Zen 60m.
- `n20/a.java`: Touch Grass 8h, Quiet Day 24h, Digital Sabbath 48h, Retreat 5 days.
- `j20/h.java:131` and `mn/q.java:322`: shared hold-to-start component.
- `yr/w.java:55–59`: that component declares a default 2-second hold and a 300ms
  initial collapse phase. [The exit DEX trace](opal-4.14.0-timer-exit-trace.md) additionally
  establishes a 400ms finish phase and 400ms callback delay. Gesture accessibility and rendering still need
  runtime comparison; the shader/media source is not imported into Flint.

Flint's initial dedicated Timer implements finite app selections and the recovered
durations/presets, using its existing break tiers. Infinity, sites/allow-only selection,
Opal's separate strict-rule versus leave-early behavior, hold gestures, and Detox media
remain distinct parity items. Do not describe the finite milestone as a complete Timer clone.
