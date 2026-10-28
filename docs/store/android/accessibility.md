# Accessibility API declaration draft

Prepared 2026-10-28. **Hold submission until lane C removes release
Settings/uninstall/disable interception and E4 copy matches the resulting code.**
The baseline guard is not justified by consent or the fact that other apps ship
similar behavior. [Full sensitive-API policy](https://support.google.com/googleplay/android-developer/answer/16558241)
restricts preventing disable/uninstall to parental-control and enterprise cases;
Flint is neither. No exception or approval is claimed.

## Proposed answers / reviewer explanation

Flint is a personal focus app. It is not primarily a disability-assistance tool
and does not declare `isAccessibilityTool=true`.

With affirmative in-app consent, Accessibility identifies the foreground app
and reads the address bar in supported browsers. Flint compares that information
locally with the user's selected apps, domains, schedules and limits, then shows
a blocking screen when a rule applies. Known address-bar IDs are used; browser
updates, inaccessible bars or unsupported browsers can reduce website coverage.
No raw URL history or screen content is uploaded. There are no accounts,
analytics, ads or remote commands in the release.

The intended submission build does not read Settings screen text for guarding,
intercept system uninstall/disable controls, change settings automatically, or
prevent the user from revoking access. It does not automate purchases/messages
or autonomously plan actions. App blocking follows user-defined deterministic
rules. Accessibility's window-content capability exists to read supported
browser address bars; that technical capability must not be described as no
screen access.

All in-app Accessibility-enable entry points, including Today setup and the
You/Settings Fix button, show the same dedicated prominent disclosure. Accept
and continue opens Android Accessibility Settings; Not now/Back returns without
opening Settings. The system still owns the grant. Re-enabling is also gated.
The system service description supplements this disclosure, not replaces it.
Users can instead use Usage Access plus overlay for limited app-only fallback;
website blocking is unavailable through that path.

For any question about data **accessed**, name foreground/installed app activity
and supported-browser URL data. For **collected/shared**, reconcile the form's
wording against [the inventory](data-safety.md); local access is not developer
collection. Do not assert zero sensitive-data access.

## Demo-video shot list (owner recording)

Record the final release build on a clean test device using fictional rules and
non-sensitive browser addresses. Include the source SHA, version and device/API
in the private submission evidence record; do not expose keys or personal data.

1. Open Flint with Accessibility disabled. Navigate from setup to the dedicated
   disclosure, showing its entire text; scroll slowly if necessary.
2. Tap Not now, show the return destination and that system Settings did not open.
   Reopen the disclosure. Repeat through the You/Settings Fix entry to demonstrate
   the same gate; demonstrate Back/decline there too.
3. Accept and continue, show Android's own Accessibility service description and
   grant screen, then manually enable the service and return to Flint.
4. Configure a fictional app rule and show its block. Explain foreground app use.
5. Configure a harmless test domain in a supported browser and show its block.
   Explain address-bar reading, local processing and browser limitations.
6. With an active Hardcore rule, freely open the service's system disable control
   and Flint's app-info/uninstall controls. Show no guard/interception and normal
   revocation; do not actually uninstall until the recording no longer needs the
   app. Reopen Flint and show truthful degraded-path guidance.
7. Show Usage Access/overlay fallback and its app-only limitations, without
   implying equivalent website protection.

Add narration/captions where use is not obvious. The owner must create an
accessible video URL and complete the actual declaration; no video is produced
or uploaded by these drafts. [Official declaration and video requirements](https://support.google.com/googleplay/android-developer/answer/10964491),
accessed 2026-10-28.
