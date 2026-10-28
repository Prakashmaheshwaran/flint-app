# C5 website coverage

PR: https://github.com/Prakashmaheshwaran/flint-app/pull/153, base `integration/v1`,
branch `v1/c/web-coverage`; never merged by this worker.

Implemented: versioned 25-browser adapter matrix; process-local degraded/unsupported/paused
website coverage in existing health UI; shared 500 ms leading/trailing URL-read throttle;
supported-browser/active-domain/window filtering. Existing allow-list editor note preserved.
No changes to the five prohibited hot files. Coordinator approved additive health mapping
and reassigned service/consent/store disclosure copy to C5; enable routing is unchanged.

Tests added: 30 (matrix 1, coverage policy/tracker 7, throttle 5, service 10, health 5,
health presentation 1, consent copy 1). Existing consent-navigation tests preserved.
Local targeted execution passed 380 tests: engine 260, accessibility 12, resilience 42,
Settings 46, onboarding 20; no failures. A shadow fixture's conflicting `text` name was
fixed before that successful run. The final additional activity-window identification
test awaits CI; another local run was deferred at 31% free memory under the coordinator's
35% threshold. Full Android build/unit/release-local-first gate runs on PR CI; consult
the PR's exact-head checks and the planning handoff report for final live CI state.
Device evidence: none; shared device slot occupied. CPU/event-rate cost is unit-tested only.

Risks/follow-ups: browser-version/real navigation/private-mode acceptance remains unverified;
adapter `verifiedAt` fields say so explicitly. Settings refreshes coverage on resume.
Separate final-diff consent/enforcement review is lead-owned. Never merged by this worker.
