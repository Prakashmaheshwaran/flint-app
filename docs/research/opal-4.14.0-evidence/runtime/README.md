# Opal 4.14.0 runtime capture — onboarding

Captured locally on 2026-10-11 from the dedicated `flint-opal-reference` emulator, serial `emulator-5556`, Android 15 / API 35, 1080 × 2400. The user completed Google Play sign-in; Opal was then installed from its normal Play listing. No APK modification, entitlement override, or account-data inspection was used.

## Provenance

[`package-version.txt`](package-version.txt) is the filtered package-manager readback: package `com.withopal.opal`, version `4.14.0`, code `4014000`, installer `com.android.vending`, minimum API 29, target API 36. [`android-release.txt`](android-release.txt) and [`android-sdk.txt`](android-sdk.txt) record the reference OS. [`SHA256SUMS`](SHA256SUMS) records selected artifact hashes.

Screenshots are direct `adb -s emulator-5556 exec-out screencap -p` captures. XML files are `uiautomator dump` results from the same screen, captured shortly after the screenshot. Animation means the two are not frame-synchronous. All raw attempts remain in ignored `android-app/build/opal-analysis/runtime-reference/`; selected evidence is retained here. No emulator-5554 commands were issued by the capture agent.

## Actions and observations

| Evidence | Action leading to screen | Observed result |
| --- | --- | --- |
| [`003-relaunch.png`](003-relaunch.png) | Open from Play; after a prolonged splash, perform one normal force-stop/relaunch of `.MainActivity` | Animated stone intro, “An Opal only reveals its fire…” and “TAP TO CONTINUE.” UIAutomator returned a null root during this animation. |
| [`005-name.png`](005-name.png), [`005-name.xml`](005-name.xml) | Tap the intro's continue area | Name onboarding, “I'll help protect your focus,” “First, what's your name?”, placeholder “John,” and Continue. The screenshot also shows an emulator input-method toolbar. |
| [`011-met-before.png`](011-met-before.png), [`011-met-before.xml`](011-met-before.xml) | Enter synthetic test text and Continue | “Quick check, have we met before?” with “Yes, sign in” and “I'm new to Opal.” Intended input “Reference” was reordered by the emulator to the displayed synthetic name “Reerencef.” This is not a user's identity. |
| [`013-auth-choice.png`](013-auth-choice.png), [`013-auth-choice.xml`](013-auth-choice.xml) | Select “I'm new to Opal” | Provider choices: Google, email, phone. No guest/Skip control appears; the hierarchy contains no scrollable container. |
| [`014-email-form.xml`](014-email-form.xml) | Open “Continue with email,” without entering or submitting anything | Empty Email and Password inputs, Show password, Sign up, and Back. The initial screenshot caught an animation frame and was omitted from durable evidence. |

The first attempt to dismiss the presumed keyboard with system Back exited Opal; reopening returned to name onboarding. No data clear or reinstall was used. The reference emulator initially stalled on some input/dump commands; progress improved after the parent agent paused its separate test emulator. The initial Play XML left in the ignored raw directory is stale because a subsequent dump returned a null root; it is not evidence of the Opal screen.

## Boundary reached

The new-user route requires an Opal authentication action before the requested home, My Apps, timer, profile, statistics, rule sheets, and personal-limit screens become reachable. These screens have **not yet been observed in this runtime capture**. Static source evidence elsewhere in this folder is not a substitute for observing those screens.

No Google provider action, email/password/phone submission, remote-account registration, trial, purchase, subscription, invitation, institution membership, or message was performed by the agent. No Opal restriction permission was requested or granted before this gate. Opening the email route only displayed its empty form.

The parent asked the user to handle Opal authentication. UI interaction and capture were stopped for that handoff. A capture already in flight had reopened the empty email form; the user may need its top-left Back button to return to provider choices. Two captures overlapping the handoff were discarded without inspection. Continue ordinary-screen captures only after the user confirms authentication is finished.

### Authentication follow-up

The user's subsequent attempt failed with “Firebase App Check token is invalid.” A read-only, credential-redacted diagnosis found synchronized time, validated connectivity, enabled Play services, and App Check rejection in both signup and phone-verification logs. Ordinary screens remain blocked behind authentication. See [auth-diagnosis.md](auth-diagnosis.md) for evidence, the emulator-eligibility inference, and the limits of that inference.
