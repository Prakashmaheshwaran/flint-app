# Security Policy

Flint is local-first: no accounts, no backend, no telemetry — your blocklists and usage data
never leave the device. That keeps the attack surface small, but a blocker holds privileged OS
capabilities (Screen Time on iOS; AccessibilityService + overlay on Android), so security
reports are taken seriously.

## Reporting a vulnerability

**Please do not open a public issue for security problems.**

Use GitHub's private vulnerability reporting: **Security tab → "Report a vulnerability"** on
this repository. You should get an initial response within 7 days.

Especially in scope:

- Bypasses of enforcement Flint claims is non-bypassable (Deep Focus / Hardcore, anti-bypass PIN)
- Abuse or escalation via the Android AccessibilityService or overlay layer
- Anything that sends user data off the device — Flint makes **no network calls at all**, so
  any network traffic you find is a bug by definition

Out of scope: OS-level limitations already documented honestly in the repo (e.g. iOS
Open-Limits enforcement constraints, Simulator shield limitations).

## Supported versions

Pre-1.0: only the latest release and `main` are supported.
