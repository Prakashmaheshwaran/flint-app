# F6 — Insights authorization recovery verification

Insights now uses F5's production authorization controls. Denied access offers **Open Settings** and one **Check access again** action. Undecided access retains transient-error retry, and a pending request offers **Stop waiting**. The host no longer tells denied users to request access again. Approval, revocation, foreground refresh, calendar ranges and report rendering continue to use the shared model.

## Source and scope

- Product: `c264440100d60fe767825c3f446c49c3c9c67fd5` on `codex/f6-ios-insights-states` / PR #65.
- Dependency: F5 `d4b52f2028254e4f5f7537e8ee57edc8aec187fc`, integrated by a normal local merge.
- Unit tests import the production Flint module. The report extension's presentation sources remain in the test target; the authorization model and Insights state are not duplicated there.
- These artifacts supplement the [original F6 verification](../f6-ios-insights-states-2026-10-24/README.md), which remains unchanged historical evidence.

## Results

| Check | Result |
| --- | --- |
| Full `make verify-ios` gate | 82 passed, 0 failed or skipped |
| Native UI at Large | Four accepted passing cases: three unchanged initial cases plus the targeted denied-case rerun |
| Native UI at AX5 | 4 passed, 0 failed or skipped |
| Report presentation renders | Six visually reviewed: absent data, recorded zero usage and positive activity at Large/AX5 |

The native suite uses a freshly installed own-branch binary on the authorized temporary iPhone 18 Pro / iOS 27 guest. It covers denied access in Today and 7 days, opening Settings, returning and rechecking, a full multiline transient error followed by retry, selected-range preservation through approval, a pending request surviving foreground refresh, Stop waiting followed by recovery, and timeout followed by retry. Approved Simulator states continue to explain that a physical device is needed.

The first Large pass had one harness failure: it assumed the native Settings navigation title would be Flint. The captured hierarchy showed the Settings root page. The corrected harness verifies that the native Settings app opened, records the actual destination, and completes return/recheck. The other three Large cases already passed and were retained; only the failed denied case was rerun before the first full AX5 pass. No product source changed. The successful full gate and three Large cases were reused after checking the exact source revision, executable hashes, unchanged case bodies and shared UI helpers. The first-attempt harness and logs are retained for provenance. The manifest keeps the original failed run summary and identifies the eight accepted native cases individually.

After the second run's successful test commands, its temporary shell runner exited 127 while printing the ending timestamp (`-u: command not found`). The runner had been edited while active, which disrupted its final shell read. The cleanup trap still completed: all four cleanup commands returned zero, Large was read back, PIN remained absent, and the fixture app process was replaced by a normal launch without preview arguments. This logging failure is retained in the sequence log and cleanup metadata; test outcomes come from the result bundles. The archived runner has the intact `date -u` postamble. No passing tests were repeated for this logging issue.

## Native screenshots

| State | Large | AX5 |
| --- | --- | --- |
| Denied, 7 days | [View](screenshots/large-01-denied-week.png) | [View](screenshots/ax5-01-denied-week.png) |
| Denied, Today | [View](screenshots/large-02-denied-today.png) | [View](screenshots/ax5-02-denied-today.png) |
| Shared Settings guidance | [View](screenshots/large-03-denied-guidance.png) | [View](screenshots/ax5-03-denied-guidance.png) |
| Open Settings action | [View](screenshots/large-04-denied-settings-action.png) | [View](screenshots/ax5-04-denied-settings-action.png) |
| Actual Settings destination | [View](screenshots/large-05-settings-destination.png) | [View](screenshots/ax5-05-settings-destination.png) |
| Return and recheck | [View](screenshots/large-06-denied-after-settings-and-recheck.png) | [View](screenshots/ax5-06-denied-after-settings-and-recheck.png) |
| Transient error | [View](screenshots/large-08-transient-error.png) | [View](screenshots/ax5-08-transient-error.png) |
| Retry action | [View](screenshots/large-09-transient-retry.png) | [View](screenshots/ax5-09-transient-retry.png) |
| Pending request | [View](screenshots/large-12-pending-stop-waiting.png) | [View](screenshots/ax5-12-pending-stop-waiting.png) |
| Stop waiting, no error | [View](screenshots/large-14-stopped-without-error.png) | [View](screenshots/ax5-14-stopped-without-error.png) |
| Timeout guidance | [View](screenshots/large-16-timeout-guidance.png) | [View](screenshots/ax5-16-timeout-guidance.png) |
| Recovery after timeout | [View](screenshots/large-18-recovered-after-timeout.png) | [View](screenshots/ax5-18-recovered-after-timeout.png) |

All attachments, including additional range/recovery screenshots and six report renders, are indexed with their exact result bundle and attachment metadata in [evidence-index.json](evidence-index.json). [manifest.json](manifest.json) records test summaries, source and image checksums, binary provenance, and cleanup checks. The harness and compressed logs are included alongside these files.

## Cleanup and limits

- Each F6 sequence used the shared `F6-review` lock and only guest `3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473`; no extra guest was created.
- Read-only PIN checks were absent before/after installation and at cleanup. No PIN data was cleared.
- Each sequence restored Large and read it back, terminated the fixture launch, and launched Flint normally without preview arguments. These are end-of-run observations; later owners may change the shared guest.
- DEBUG authorization scenarios exercise presentation/model recovery only. They do not grant, deny, revoke or persist system authorization. On this Simulator, Open Settings reached the Settings root page; no Flint permission switch or actual authorization change was verified, and no Settings value was changed.
- Real physical-device authorization/Settings recovery, real usage, Apple's report service and VoiceOver speech remain unverified. The six report images are synthetic production-content renders outside Apple's service.
- AX5 controls were reached and activated, with multiline labels and messages wrapping. The standalone branch still has the known shared floating-tab-bar limitation: tab labels truncate and the bar partially overlays content, including the lower part of Check access again / Stop waiting in some captured scroll positions. This is not a claim of complete integrated layout acceptance. F4 owns the safe-area/tab layout and must verify these flows after consuming this product commit; F6 does not back-merge F4.
