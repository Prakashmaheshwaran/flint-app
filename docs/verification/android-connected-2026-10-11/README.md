# Android account and group milestone

Verified 2026-10-11 on `flint-test`, API 35 arm64, 1080×2400, density 420,
font scale 1.0. This is Flint's own local test service, not an Opal account or a
deployed production service.

## Build and automated checks

- `make verify-android` passed for the integrated account/group build.
- APK SHA-256: `c1f1a3443254d6809d03b036559b2ea713c6dca22578966f1b0105b757afdfc8`.
- The APK pulled from the emulator matched the built artifact.
- [Android test summary](android-tests.json): 885 variant executions / 519 unique
  test cases, zero failures or errors. Includes 15 new connected input/state cases
  in each Android variant.
- [Backend checks](backend-tests.md): 12 real Firebase-emulator integration cases,
  including access control, token revocation, invite expiry/replay/concurrency and
  direct Firestore denial.

## Actual Android round trip

1. Created a synthetic owner with Flint's account screen, edited its profile name
   to **Android Owner**, and created **Parity Family**. Independent API reads
   confirmed the stored profile and owner role.
2. Opened the group and generated an invitation using the Android UI.
3. Signed out, signed in as a second synthetic member, previewed that invitation,
   and explicitly joined. Both members appeared in the UI.
4. Independently authenticated as the member and read the group/member endpoints:
   **2 members**, actor role **member**, names **Android Owner / Android Member**.
   See [sanitized readback](ui-readback.json).
5. Force-stopped and relaunched Flint, returned to Account, and observed the
   restored Firebase identity, profile and group from the service.
6. Switched from Account to Stats and pressed Back; Home appeared. Signed out
   and confirmed profile, group and invitation state disappeared.

| Capture | Observed state |
|---|---|
| [Owner group](01-owner-group.png) | Persisted owner and single-member group |
| [Joined member](02-member-group.png) | Member role and two named members |
| [After process restart](03-restored-account.png) | Restored identity and refreshed group |
| [After sign-out](04-signed-out.png) | Empty authentication form; no former account data |

Each screenshot has a companion UI XML capture. Passwords, tokens and invitation
codes are excluded from durable evidence. The two test identities and group are
kept in the local emulator data for subsequent work.

## Limits

These captures precede the three-tab Timer navigation milestone. They establish
account/membership behavior at the stated APK hash, not later screen layout.
Group membership does not yet enforce managed rules. Google/phone sign-in,
production hosting, billing, rewards, sync and institution administration remain
separate work. No real invitation was sent and no purchase was made.
