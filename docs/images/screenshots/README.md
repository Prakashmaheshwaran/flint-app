# Flint screenshots

README preview captures taken 2026-11-05.

| File | Platform | Screen | Capture |
| --- | --- | --- | --- |
| `ios-today.jpg` | iPhone 17 simulator, iOS 27.0 | Today | `xcrun simctl io ... screenshot` |
| `ios-rules.jpg` | iPhone 17 simulator, iOS 27.0 | Rules | `xcrun simctl io ... screenshot` |
| `android-today.jpg` | `flint-test` AVD, API 35 arm64 | Today | `adb exec-out screencap` |
| `android-session.jpg` | `flint-test` AVD, API 35 arm64 | Focus session (30m, Easy, 16 apps resting) | `adb exec-out screencap` |

## Capture notes

- The iOS build used ad-hoc simulator signing so the App Group container was available.
  Screen Time authorization still cannot be granted in the simulator, so the Today capture
  shows the setup card rather than an authorized state.
- Android usage access, Flint's accessibility service, and the battery exemption were
  granted over adb to reach the healthy "Blocking is on" state.
- These are UI previews only. They do not demonstrate Screen Time authorization, shield
  delivery, background survival, or physical-device enforcement.
