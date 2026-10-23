# Opal authentication error on the reference emulator

Read-only diagnosis on 2026-10-11, after the user attempted authentication. No authentication attempt, account selection, SMS request, retry, account-data reset, or UI navigation was performed by the diagnosing agent. No screenshot was taken during this diagnosis. Only error text was extracted from the UI; entered fields were excluded.

## Verified

The visible error was:

> An internal error has occurred. [ Firebase App Check token is invalid. ]

The [sanitized diagnostic record](auth-diagnostics-redacted.txt) shows the same App Check rejection for both the user's email/password signup operation (`signUpPassword`, 01:56:27 device-local log time) and phone-verification operation (01:58:07, FirebaseAuth status `17499`). This is an App Check validation failure surfaced through the authentication SDK. The reCAPTCHA wrapper name alone does not establish a failed CAPTCHA challenge.

Other checked conditions:

- Device UTC and host UTC matched to the second at both observations. Automatic time and time-zone flags were enabled.
- Android reported validated Internet connectivity. This confirms basic network availability; it does not prove that every Opal/Firebase endpoint was reachable.
- Active Google Play services: `26.37.68 (260400-995428298)`, code `263768035`, installed, not suspended, default enabled state. Active Play Store: `53.4.34-34`, code `85343440`, installed and explicitly enabled.
- Opal remained the Play-installed `4.14.0` / `4014000` package.
- Play Integrity bound to the Play Store service and delivered an integrity-token callback around the failed signup. A callback is not evidence that the server accepted its attestation verdict.
- `ro.kernel.qemu=1` confirms this is an emulator.

## Interpretation and limit

The strongest local evidence is a **server-side App Check rejection during authentication**, with ordinary screens therefore blocked behind authentication. Clock skew, absent Play services, and total network loss are not supported by these observations.

An emulator eligibility problem is plausible. Firebase documents emulators as an example of environments that App Check normally does not classify as valid, and describes configurable Play recognition, licensing, and device-integrity requirements. The exact rejection condition for Opal is unknown: no server configuration or attestation verdict was inspected. A production app or server configuration issue remains possible. [Firebase: App Check with Play Integrity](https://firebase.google.com/docs/app-check/android/play-integrity-provider)

## Safe next action

Use the unmodified Play-installed Opal on a supported physical Android device and let the user perform authentication, then capture ordinary screens after confirmation. If the same error occurs there, give Opal support the app version and exact error text. Until then, continue the APK-backed analysis and label home, My Apps, timer, profile, statistics, rule sheets, and personal limits as unobserved at runtime.

No attestation or licensing bypass was attempted. No credential or token values are retained in these durable diagnostics; email, phone, nonce, URL, and long secret-like values were redacted before the diagnostic record was written.
