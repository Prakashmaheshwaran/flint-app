# Security Policy

Flint's local blocking engine keeps blocklists and usage data on the device. The optional
Android connected-service development milestone adds local test accounts, profiles, groups,
invitations and managed-policy authoring. Those operations send account/membership data and
explicitly authored managed targets/schedules to the configured local Firebase emulators and
Flint API; no production service is configured yet. Personal blocklists, usage history and
installed-app inventories are not uploaded. The client includes no analytics SDK. Connected
tooling is a debug-only dependency; release APKs contain no connected client or Firebase
account SDK, and their Settings screen has no account entry.

A blocker holds privileged OS capabilities (Screen Time on iOS; AccessibilityService + overlay
on Android), and the account service introduces authentication/authorization boundaries.
Security reports are taken seriously.

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Use GitHub's private vulnerability reporting: **Security tab → "Report a vulnerability"** on
this repository. You should get an initial response within 7 days.

Especially in scope:

- Bypasses of enforcement Flint claims is non-bypassable (Deep Focus / Hardcore, anti-bypass PIN)
- Abuse or escalation via the Android AccessibilityService or overlay layer
- Unrequested transmission of usage, app selections, credentials or account data
- Missing account/group authorization, reusable or leaked invitation credentials, session
  mix-ups, or direct database access outside the authenticated service
- A debug emulator endpoint or unsigned emulator token accepted in a production environment

Out of scope: OS-level limitations already documented honestly in the repo (e.g. iOS
Open-Limits enforcement constraints, Simulator shield limitations).

## Supported versions

Pre-1.0: only the latest release and `main` are supported.
