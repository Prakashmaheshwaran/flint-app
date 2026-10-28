# C5 website coverage

PR: draft pending creation, base `integration/v1`, branch `v1/c/web-coverage`.

Implemented: versioned 25-browser adapter matrix; process-local degraded/unsupported/paused
website coverage in existing health UI; shared 500 ms leading/trailing URL-read throttle;
supported-browser/active-domain/window filtering. Existing allow-list editor note preserved.
No changes to the five prohibited hot files. Coordinator approved additive health mapping
and reassigned service/consent/store disclosure copy to C5; enable routing is unchanged.

Tests added: 26 (matrix 1, coverage policy/tracker 7, throttle 5, service 7, health 4,
health presentation 1, consent copy 1). Existing consent-navigation tests preserved.
Execution: pending CI; local Gradle held by coordinator's 35% free-memory prerequisite.
Device evidence: none; shared device slot occupied. CPU/event-rate cost is unit-tested only.

Risks/follow-ups: browser-version/real navigation/private-mode acceptance remains unverified;
adapter `verifiedAt` fields say so explicitly. Settings refreshes coverage on resume.
Separate final-diff consent/enforcement review is lead-owned. Never merged by this worker.
