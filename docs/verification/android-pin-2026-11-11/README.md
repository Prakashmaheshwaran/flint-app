# Android app-open PIN — local emulator evidence (2026-11-11)

Feature F03 App-open PIN, Android ([PR #256](https://github.com/Prakashmaheshwaran/flint-app/pull/256)).
This is emulator evidence only, not a physical-device result.

## Environment

- AVD `flint-test`: Android 15 (API 35), Google APIs, arm64-v8a, on the shared Apple Silicon host.
- Serial `emulator-5554`, boot incarnation `c8f1f90d-2f43-46de-af39-44fc47714afb`. The emulator was booted for this run and shut down afterwards.
- Debug build of `feature/android-pin` at ee1b85d. The gate screenshots were taken after 55ac6dc, which only removes the ember orb behind the gate.

## Instrumented test

```sh
./gradlew -PflintE2E=true :e2e:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.flint.peakfocus.e2e.AppPinTest \
  -Pandroid.testInstrumentationRunnerArguments.flintDisposable=true \
  -Pandroid.testInstrumentationRunnerArguments.flintBootId=<boot id> \
  -Pandroid.testInstrumentationRunnerArguments.flintRunToken=<uuid>
```

Result: `AppPinTest.appPinGatesContentAndRecoveryStaysReachable` passed (1 test, 0 failures, 115 s). The test:

1. Sets a PIN from You › App lock.
2. Force-stops and relaunches Flint, then checks that the gate shows and the tab labels are not in the accessibility tree.
3. Checks that a wrong PIN shows "Incorrect PIN".
4. Opens "Emergency Pass & recovery" from the gate. The locked panel appears, and "Open Emergency Pass & recovery" reaches the protection recovery screen (BlockActivity) without the PIN. Back returns to the gate.
5. Checks that the correct PIN unlocks Flint.
6. Removes the PIN, which asks for the current PIN.
7. Relaunches and checks that no gate appears.

Only this class was run. The whole suite runs in the hosted `Android emulator E2E` workflow.

## Manual checks (T3 device tools)

| Check | Observation | Evidence |
| --- | --- | --- |
| Gate on resume | After setting a PIN, going Home and reopening Flint showed the gate. Its accessibility snapshot listed only the PIN field, Unlock, "Emergency Pass & recovery" and "Forgot PIN?". No tab or settings content was exposed. | [gate.png](gate.png) |
| Recents after leaving | After setting a PIN and going Home, Flint's Recents card is blank (`setRecentsScreenshotEnabled(false)`, API 33+). | [recents-after-leaving.png](recents-after-leaving.png) |
| Recents live tile (limit) | Opening the app switcher directly from inside an unlocked Flint shows Android's **live tile**: the activity is still `RESUMED`, so the card shows the real surface the user was just looking at. No screenshot is stored. Once Flint is left, the card is blank and Flint resumes at the gate. iOS draws a privacy curtain on scene deactivation; Android has no lifecycle callback for the live tile. | [recents-live-tile-limit.png](recents-live-tile-limit.png) |
| State kept across a lock | Unlocking returned to the same tab and scroll position, because content stays composed (hidden) behind the gate. | — |
| Largest system text (`font_scale` ≈ 2) | The gate scrolls, Unlock and the recovery entry stay reachable, and the font change (a configuration change) did not drop the lock. Font scale was restored to 1.0 afterwards. | [gate-largest-text.png](gate-largest-text.png) |
| Locked recovery panel | Shows no rule or session names, apps, sites or history. It offers Emergency Pass & recovery, Flint's App info, and the forgotten-PIN route. | [locked-recovery-panel.png](locked-recovery-panel.png) (taken before the orb change, which does not affect this panel) |

## Not verified

- Physical devices or OEM skins.
- Spoken TalkBack output.
- Hardware-keyboard/D-pad focus redirection (ee1b85d).
- `FLAG_SECURE` on API 23–32, which is covered only by the policy unit test.
- Multi-window and split-screen.
- Reboot and lockout behaviour on a device, which is covered by JVM tests with a fake clock.
