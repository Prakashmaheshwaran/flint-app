# My Apps and Timer

`BlocklistScreen` manages app/site rules, schedules, limits, saved target groups, and sleep
rules. `TimerScreen(modifier: Modifier = Modifier)` is the dedicated finite-session entrypoint.
Both use Flint's observable core-datastore stores and existing blocking engine.

## Timer behavior

- Default duration is 30 minutes. The duration picker offers 5–120 minutes in five-minute
  steps, then 3–24 hours in hourly steps.
- Focus presets: Deep study (90m), Commute (30m), Workout (60m), Reading (30m), and Weekend
  zen (60m). Digital detox duration presets: Touch Grass (8h), Quiet Day (24h), Digital
  Sabbath (48h), and Retreat (5 days).
- Presets set duration/title only. Users choose their own apps and existing Flint break
  level: Easy, Harder, or Hardcore. These tiers retain Flint's existing behavior; they do
  not implement Opal's separate Standard/Increasing access-limit settings.
- Start snapshots the selected apps into an enabled, unscheduled `BlockRule` under the
  `session-` prefix, with a persisted absolute expiry. It never changes the legacy quick
  blocklist, permanent rules, schedules, or limits.
- `startSessionIfIdle` atomically checks for existing live sessions before inserting.
  Repeated taps and concurrent starts cannot replace an active session.
- Active sessions restore from the rule store after navigation or process recreation.
  Earlier Home sessions are included. If old data contains several, each is shown and
  another start stays disabled. A saved session without an end time is shown honestly.
- Selection/duration/tier cannot change while any session is active. Native Timer sessions
  (`session-timer-`) use **Leave early → Hold to leave** at every Flint break level.
  Releasing before two seconds resets progress. Completion runs a 400ms finish phase and
  a 400ms callback delay, then atomically deletes only the fully matching saved Timer.
  A changed name, target, tier, schedule, deadline or enabled state requires a fresh
  confirmation. Other rules and all temporary grants remain untouched.
- Older Home sessions retain their earlier rules: Easy cancellation uses
  `cancelEasySession`; legacy Harder/Hardcore early cancellation remains unavailable.
- Leaving the screen or recreating the process cancels an incomplete hold. After the
  two-second threshold, release/dismissal cannot revoke confirmation; successful storage
  deletion is required before displaying success. Process death can still interrupt a
  pending write, and reopening shows the persisted outcome.
- Keyboard users can hold Enter/Space; loss of focus cancels an incomplete hold.
  Accessibility activation starts the same cancellable countdown without requiring a
  sustained pointer, and **Never mind** cancels it before completion. This fallback is a
  Flint design decision; it has not been verified with TalkBack on a physical device.
- The visible countdown reads the persisted deadline, updates every second while the
  screen is visible, and refreshes immediately on resume. Expiry returns the screen to
  the draft without requiring a cleanup write. The existing engine checks expiry
  independently; other overlapping rules may continue blocking an app.
- Rule/app loading errors are distinct from empty results and have retry actions. A failed
  mutation retains the draft or running session. Drafts survive configuration changes
  through the ViewModel; only active sessions are persisted across process death.

## Reference and fidelity

The finite choices and preset durations are grounded in the supplied Opal 4.14.0 APK's
duration list (`j20/i0.java`), standard presets (`j20/f0.java`), and detox models
(`n20/a.java`) under `android-app/build/opal-analysis/decompiled/sources/`.

This milestone uses Flint components and contains no copied Opal assets or implementation.
It does not yet implement Opal's unlimited sessions (whose open-ended storage semantics
have been recovered), Timer allow-only/web selections, or the distinct
Digital Detox media/engagement flow. Actual Timer reference screenshots are still needed
for visual, animation, gesture, and accessibility parity. Presets do not automatically
classify or select distracting/social apps.

The [DEX-checked exit trace](../../../docs/research/opal-4.14.0-timer-exit-trace.md)
establishes that Opal's strict Timer can be ended manually without creating a general
break. Flint implements that separation for its native Timer namespace, while retaining
its existing break tiers. The hold uses an original visual treatment with a 300ms initial
compression; matching reference pixels and haptic behavior remain unverified.

## Focused checks

`TimerModelsTest` covers supported durations, presets, target snapshots, break tiers,
restoration/expiry filtering, and countdown boundaries. `TimerViewModelTest` covers
restoration, loading, start races, cancellation eligibility, mutation failures, picker
preservation, lifecycle clock refresh, exact hold phases, release/reset, stale confirmation,
duplicate prevention and process recreation with an injected clock and coroutine dispatcher.
Store contract tests cover concurrent ending at every tier and preservation of overlaps.

```sh
cd android-app
JAVA_HOME=/opt/homebrew/opt/openjdk@21 ./gradlew :feature:feature-blocklist:testDebugUnitTest
```

The root Android gate is `make verify-android`. Device acceptance additionally checks actual
blocking, expiry in the foreground/background, process recreation, overlapping rules,
and app-picker/back behavior. A passing unit test run alone does not establish device or
visual parity.
