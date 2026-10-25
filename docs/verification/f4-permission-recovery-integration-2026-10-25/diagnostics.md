# Historical diagnostics

These observations belong to initial source `85f066766fe03c75826f73c033a284903e414817`. They remain visible alongside the final `5e62cb8` acceptance and do not count as final passes.

## Secondary accessibility-frame assertion

The two Large denied cases in `ui-01` passed whole reported control containment and public Settings activation/return, then failed the strict minimum-44pt assertion for Check access again. Insights reported `(139.3, 514, 123.7, 18.3)` and Today `(139.3, 689, 123.7, 18.3)`. Both reported frames were hittable and above the tabs. **The actual recheck tap had not run when the assertion stopped each case.** The exact assertion height was 18.333pt. [Observations](manifests/secondary-target-observations.json), [initial summary](manifests/ui-01-large-summary.json).

This AX rectangle alone did not prove that the physical touch target was only 18.333pt. To distinguish those claims, separate pending-fixture probes observed actual taps outside the Stop waiting label:

| Observation | Value |
| --- | --- |
| Reported AX frame | (162.667, 689, 77, 18.333) |
| Intended 44pt layout area | (162.667, 676.167, 77, 44) |
| Lower off-label probe | (201.167, 719.167), 21pt below center: cancellation observed |
| Upper off-label probe, fresh fixture | (201.167, 677.167), 21pt above center: cancellation observed |

Both taps activated cancellation, so the observed vertical touch span was at least 42pt. The AX label frame understated the observed touch region. **This was not a failed-touch reproduction or proof of a short physical target.** Both diagnostic tests deliberately retained the strict AX-height assertion after recording their successful tap observations and therefore ended with exit 65. [Lower probe](manifests/stop-waiting-probe-01.json), [upper probe](manifests/stop-waiting-probe-02-upper.json), frozen sources and logs in `harness/` and `logs/`.

F5 subsequently moved the full-width/minimum-height frame and content shape inside the secondary labels, inherited by F6 and then F4. The final affected suite retains the strict size/containment assertions and adds corner cancellation on both screens at Large and AX5. All eight pass; the final AX frame and actual corner behavior are both established. F4 did not patch or copy the shared component independently.

The four initial AX5 cases passed (two denied, two transient). The denied passes are superseded; the two transient cases and the two Large transient passes are retained with exact source attribution and unchanged-path evidence.

## Toolchain transition and cleanup recovery

Initial gate/product `85f0667` was built with Xcode 27.0 / 27A266a and Simulator27.0 SDK. During `ui-01` cleanup, `simctl` observed CoreSimulator framework 1174.9.2 versus existing service 1171.7 and replaced the stale service. The authorized guest then reported Shutdown; the first Large restore and plain launch returned 149. The installed toolchain subsequently reported Xcode 27.1 / 27A9275. The record establishes this transition, not who or what updated Xcode.

Recovery ran under the shared lock, booted only the already-authorized guest, restored and read back Large, verified no PIN, and relaunched the production app without fixtures. Recovery exited successfully. The reviewer guest and unknown data were untouched. [Original cleanup](logs/ui-01-cleanup.txt), [recovery log](logs/ui-01-cleanup-recovery.log.gz), [recovery baseline](manifests/ui-01-recovery-baseline-after.json).

Initial AX5 and off-label probes used the same hash-verified Xcode27.0-built `85f0667` app with an Xcode27.1 UI harness; their cleanup succeeded. The final full gate and fresh app/native affected suite at `5e62cb8` all use Xcode27.1, and final cleanup succeeded. [Build toolchains](manifests/build-toolchains.json), [final gate](manifests/gate-02-summary.json), [final cleanup](logs/ui-03-affected-cleanup.txt).
