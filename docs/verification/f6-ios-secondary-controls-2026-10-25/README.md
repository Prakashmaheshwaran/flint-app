# F6 — Shared secondary-control verification

Insights consumes F5's corrected secondary button labels. **Check access again** and **Stop waiting** now report accessibility frames at least 44 points high, with a full-width label and rectangular content shape. The shared control API and F6's authorization, report and range logic are unchanged.

## Source and results

- F6 tested source: `3de5132e34fd2b0d6a1ee68cff7a7bfdbaac2e5b`.
- F5 source-and-evidence dependency: `08bbc204b9074e9fd56a9efa26faad96e995b155`; product fix: `08bbc204b9074e9fd56a9efa26faad96e995b155`.
- Toolchain: `Xcode 27.1 / Build version 27A9275`. Earlier F6 recovery evidence used Xcode 27.0 / 27A266a; these new results are recorded independently.
- Full `make verify-ios`: **82 passed**, none failed or skipped; full app/extensions build and hosted test suite.
- Focused native checks: **2/2 at Large and 2/2 at AX5**. Only the affected recheck and cancellation flows were rerun.

The denied case verifies the recheck Button's 44-point minimum accessibility frame, taps its upper label region, and confirms that the single recheck and Settings guidance remain. The fixed-denied fixture does not expose an actual permission transition or observable invocation counter, so the retained denied state alone does not prove a system recheck occurred.

The pending case verifies a 44-point minimum **Stop waiting** accessibility frame, taps its upper-leading padded corner (normalized x=0.05, y=0.1), observes cancellation with no error, retries successfully, and preserves **Today** in the resulting truthful Simulator state. This establishes that the tested padded region activates cancellation. F4 had already observed successful off-label taps in the old implementation despite its smaller reported accessibility frame; the old frame alone did not establish a too-small physical touch region.

The previous [authorization recovery evidence](../f6-ios-authorization-recovery-2026-10-25/README.md) and [original Insights evidence](../f6-ios-insights-states-2026-10-24/README.md) remain unchanged. Their passing semantic/range cases retain their original source and binary provenance. This follow-up changes only the shared secondary-label presentation and onboarding Not now through F5; it does not repeat the unchanged native suite.

## Screenshots

| State | Large | AX5 |
| --- | --- | --- |
| Denied recheck target | [View](screenshots/large-01-denied-recheck-target.png) | [View](screenshots/ax5-01-denied-recheck-target.png) |
| Denied after recheck | [View](screenshots/large-02-denied-after-recheck.png) | [View](screenshots/ax5-02-denied-after-recheck.png) |
| Stop waiting target | [View](screenshots/large-03-stop-waiting-target.png) | [View](screenshots/ax5-03-stop-waiting-target.png) |
| Cancellation without error | [View](screenshots/large-04-stopped-without-error.png) | [View](screenshots/ax5-04-stopped-without-error.png) |
| Today retained after recovery | [View](screenshots/large-05-recovered-today.png) | [View](screenshots/ax5-05-recovered-today.png) |

[manifest.json](manifest.json) records exact source/binary/harness checksums, measured target frames, results and cleanup. [evidence-index.json](evidence-index.json) maps the ten native screenshots to their original test attachments and includes the [ordinary-launch cleanup capture](screenshots/cleanup-large.png). The frozen runner, harness and compressed logs are included.

## Cleanup and limits

- Only authorized guest `3B8730F1-B59C-46D1-9ADA-ADC0FE7EA473` was used, under the shared `F6-review` build/device lock. No guest was created or removed.
- PIN was absent in read-only checks before installation, after installation and at cleanup. No PIN data was cleared.
- Cleanup restored and read back Large, ended the fixture process and launched Flint with no preview arguments. The immutable runner completed successfully. These are end-of-run observations; another owner may subsequently use the shared guest.
- DEBUG authorization scenarios exercise the production view/model with injected status. No real permission, Settings value or account state was changed. Real device authorization, report data/service and VoiceOver speech remain unverified.
- The standalone F6 branch still carries the shared tab-bar layout pending F4 integration. AX5 tab labels truncate, and the Stop waiting screenshot shows the lower portion of that label covered at the captured scroll position; its upper-leading padded corner was visible and activated cancellation. Recheck is fully visible in its new capture. These focused checks establish secondary-label geometry and recovery behavior, not complete integrated viewport or tab-bar acceptance. F4 must verify its composed layout after consuming this head.
