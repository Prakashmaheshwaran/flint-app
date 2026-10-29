# C5 website coverage

PR: https://github.com/Prakashmaheshwaran/flint-app/pull/153, base `integration/v1`,
branch `v1/c/web-coverage`; never merged by this worker.

Implemented: versioned 25-browser adapter matrix; process-local degraded/unsupported/paused
website coverage in existing health UI; shared 500 ms leading/trailing URL-read throttle;
supported-browser/active-domain/window filtering. Existing allow-list editor note preserved.
No changes to the five prohibited hot files. Coordinator approved additive health mapping
and reassigned service/consent/store disclosure copy to C5; enable routing is unchanged.

Tests added: 42 (matrix 1, coverage policy/tracker 9, throttle 5, service 17, health 6,
health presentation 2, consent copy 2). Existing consent-navigation tests preserved.
The preceding local suites passed **392** tests: engine 262, accessibility 19, resilience 43,
Settings 47, onboarding 21; zero failures, Android semaphore and >=35% memory held.
The first candidate c1ca27a also passed full Android CI run 40787949420, including APK/AAB
account/network exclusion. Follow-up 6a1c9f1 built all artifacts but exposed a fixture
ownership bug in CI/local tests: Robolectric returned its backing window object rather
than a separate snapshot, so recycling it mutated the fake root's window ID. The test
shadow now returns an owned copy; all local suites passed after that fix. Final full CI
is pending; consult the PR exact-head checks and planning handoff report for live state.
The final delta adds an overlay/MainActivity regression, asserts no window-info calls on
scrolling, removes window-info calls from production, and aligns URL scope/retention/revocation
copy across consent, service description, privacy policy and Data safety with a cross-file guard.

Independent review follow-up: accurate global event-delivery/filter disclosure in consent,
service description, health summary, store declaration and privacy policy; cross-file copy
guard; no full evaluation for unchanged scrolling URLs; recycled roots; live-root callback
reconciliation; app labels and conservative unsupported-link-handler wording. Missing bars
before a successful read become informational Unconfirmed after three reads; three visible
blank-node reads after a success become Degraded. Hidden/absent bars do not fabricate Degraded.
Root fallback works after restart. Last browser gaps remain visible but stop contributing to
the global health level after leaving the browser. These incorporate the lead's batched
MUST/SHOULD/nit findings and its approved narrower coverage-state clarification.
Device evidence: none; shared device slot occupied. CPU/event-rate cost is unit-tested only.

Risks/follow-ups: browser-version/real navigation/private-mode acceptance remains unverified;
adapter `verifiedAt` fields say so explicitly. Settings refreshes coverage on resume.
Separate final-diff consent/enforcement review is lead-owned. Never merged by this worker.
