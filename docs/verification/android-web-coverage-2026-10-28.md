# Android website coverage (C5 / audit R7)

Evidence boundary: source and deterministic JVM/Robolectric tests. **Device-unverified**.
No Chrome/Firefox version, typed navigation, redirects, private mode, scrolling event rate
or emulator CPU measurement was observed for this change. The shared device slot was occupied.

## Matrix v1

`BrowserAddressBars.supportedBrowsers` is the versioned adapter list shown in the existing
Settings health banner. It retains all 25 baseline package/ID pairs; the evidence field
explicitly says source IDs pinned 2026-10-28, browser version/device unverified. Listing an
adapter is not a compatibility certification. Tests pin all package IDs and families.

## Scope and cost

Only supported-browser content events, for the tracked foreground window and while an
enabled, unexpired, currently scheduled block-list rule has domains, enter the URL path.
Allow-list domains remain ignored; the existing rule-editor note is unchanged. Unknown
http(s) VIEW handlers produce unsupported coverage using narrow package-scoped queries.
No `QUERY_ALL_PACKAGES` or network permission is added.

All URL reads (window changes, content changes and boundary/publication rechecks) share
one monotonic leading/trailing throttle. There is at most one view-ID lookup per 500 ms
per current browser window. Alternate IDs rotate on misses, retaining a working ID on
success; no loop of view-ID lookups is run in a single window. The final event in a burst
gets a trailing read even if there are no later events. Window/rule/service changes cancel
or invalidate queued work. Continuous-burst cost is unit-tested only, not CPU-measured.

Three consecutive misses in the foreground activity-backed browser window mark coverage
degraded. A successful read clears it; switching browser/window resets the session.
Dialog/view window events do not establish a main application window; root/package/window
identity must match before reading/counting. A Flint overlay never counts as a missing
browser bar. Unavailable roots cannot establish a miss and do not prove readable coverage.
URL cache and health are process-local; health contains no URL/history. Settings refreshes
the snapshot on resume, as before. With active domain rules and Accessibility disabled,
the same health surface says website blocks are paused, even if app fallback still works.

## Verification and remaining acceptance

26 new tests cover matrix/version/IDs, active windows and allow-list exclusion, consecutive
misses/recovery, switches/stale windows, unsupported handlers, the leading/trailing throttle,
synthetic service filtering, health presentation and event disclosure. The existing
AccessibilityDisclosureNavigationTest retains all affirmative-consent gate checks.

Local/CI execution results are recorded in the PR and `reports/c-web-coverage.md`.
Before general website claims, manually exercise Chrome plus a non-Chromium browser:
typed URL, link, redirect, subdomain, same-tab navigation, hidden/private address bars,
unsupported browser and Accessibility-off fallback. Record browser versions, scrolling
events/second and CPU delta with the shared device semaphore. No emergency call is needed.
