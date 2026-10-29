# Android website coverage (C5 / audit R7)

Evidence boundary: source and deterministic JVM/Robolectric tests. **Device-unverified**.
No Chrome/Firefox version, typed navigation, redirects, private mode, scrolling event rate
or emulator CPU measurement was observed for this change. The shared device slot was occupied.

## Matrix v1

`BrowserAddressBars.supportedBrowsers` is the versioned known-adapter list shown in the existing
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
or invalidate queued work. Unchanged URLs still update coverage, but skip the full coordinator
evaluation and any time-budget usage query; scrolling is not a reason to query usage twice
per second. A changed foreground root is reconciled before a content callback can evaluate
its cached browser context, without scheduling another URL read. All lookup roots and
returned address-bar nodes are recycled. Continuous-burst cost is unit-tested only, not CPU-measured.

After three unsuccessful reads with no successful read in that browser window/session,
coverage is **Unconfirmed**, informational only. Missing/hidden/renamed bars and a History,
Downloads or tab-switcher-only visit cannot prove a compatibility failure. Once a window
has produced a successful read, three consecutive reads of a visible but blank/unreadable
address-bar node mark coverage **Degraded**; absent nodes do not count toward that threshold.
One successful read clears either state; switching browser/window resets the session.
Foreground root identity and the first successful read also establish evidence after
service restart, without needing a new activity event. Root/package/window identity must
match before reading/counting. A Flint overlay never counts as a missing browser bar.
Unavailable roots cannot establish a miss and do not prove readable coverage.
The URL-read/reconcile paths do not call `AccessibilityNodeInfo.getWindow()`; overlay identity
uses the attached surface and its cached window ID plus root package metadata. This
avoids a window-info cache miss becoming a synchronous system-server call for every scroll read.
URL cache and health are process-local; health contains no URL/history. The last browser
observation remains visible when opening Flint/Settings and is labeled as a last check;
foreground miss counts reset when leaving that window, and historical gaps no longer
contribute to the global health level in ordinary apps/Flint. Settings refreshes the snapshot
on resume, as before. With active domain rules and Accessibility disabled,
the same health surface says website blocks are paused, even if app fallback still works.

## Verification and remaining acceptance

42 new tests cover matrix/version/IDs, active windows and allow-list exclusion, consecutive
misses/recovery, switches/stale windows, unsupported handlers, the leading/trailing throttle,
synthetic service filtering, health presentation and event disclosure. The existing
AccessibilityDisclosureNavigationTest retains all affirmative-consent gate checks.

The five changed modules passed 392 local tests at 2edacf7. The final source/copy batch at
6c42fbd passed its three changed modules again (88 tests), including realistic View roots
with different window IDs and zero window-info calls. Final rebased exact-head CI and
smoke results are recorded in the PR and planning handoff report. The first candidate also passed full Android CI. The review
follow-up adds global-delivery disclosure guards, unchanged-scroll/stale-root/utility-screen
and root-recycling regressions. Final execution results are linked from the PR and lane
handoff report. The window fixture returns owned snapshots so resource recycling cannot
mutate its backing identity. Remaining ambiguity: a visible empty address field (for example a new-tab
or search surface after browsing) can still produce Degraded after three blank reads; this
means no readable address, not a certified browser regression. A missing bar after a prior
success retains only that last successful observation, not proof of current-page coverage.
Before general website claims, manually exercise Chrome plus a non-Chromium browser:
typed URL, link, redirect, subdomain, same-tab navigation, hidden/private address bars,
unsupported browser and Accessibility-off fallback. Record browser versions, scrolling
events/second and CPU delta with the shared device semaphore. No emergency call is needed.
