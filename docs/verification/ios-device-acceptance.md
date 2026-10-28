# iOS physical-device acceptance — lane B enforcement

Human-run on a signed development build on a physical iPhone (iOS 16 minimum and the
current release). The simulator cannot run DeviceActivity callbacks, ManagedSettings shields
or Safari web-content filters, so **every item below is unverified until a person runs it**;
unit tests and CI cover only the decision logic behind injected seams. Case IDs `PC-xx` refer
to the acceptance table in [ADR-012](../architecture/protection-contract.md) §14. This
checklist grows with each lane B PR; rows name the PR that introduced the behaviour.

Record for every run: device model, iOS version, Flint commit SHA and build number,
Screen Time authorization state, date/time/time zone, and the result (pass / fail / blocked
with notes). Attach screenshots or a screen recording where the step says so.

## Preconditions

- Fresh install of the build under test; onboarding completed; Screen Time access allowed.
- One saved app group with 2–3 everyday apps (e.g. Safari is NOT in it), one category pick.
- Settings → You shows "Protection is healthy" before starting.

## B2 — session registration and time integrity (PR #91)

| ID | Steps | Expected |
| --- | --- | --- |
| B2-01 | Start a 25-min Easy session from Today, then force-quit Flint. Wait until the end time. | Shield lifts at the end time (±1 min) without opening Flint. Reopening shows no running session. |
| B2-02 | Start a **5-min** session; force-quit Flint. Record the actual lift time. | Shield lifts at ~5 min (end warning, PC-74). If it only lifts at ~15 min, record **FAIL B2-02** — the padded carrier worked but the warning did not; Settings should show a missed-warning notice. Then switch `FlintSessionAutoClearPlan.shortSessionStrategy` to `.floor`. |
| B2-03 | Start a 10-min session; lock the phone (screen off) for the whole session. | Shield lifts at ~10 min; unlocking afterwards shows no session. |
| B2-04 | Revoke Screen Time access (Settings → Screen Time → apps with access), open Flint, try to start a session. | Start refuses with "Allow Screen Time access…"; no running-session screen; nothing is shielded. |
| B2-05 | Fill the registration pool (enable ~20 schedules/limits/open limits), then start a timed session. | Either the session starts and lifts on time, or the start is refused with "iOS wouldn't schedule this session's end…" and no session/shield appears. Never a running session that never ends. |
| B2-06 | Start a 60-min Hardcore session; immediately start a 30-min Easy session via the Start Session shortcut. Wait 30 min with Flint closed. | The Easy session ends at 30 min. The original 60-min registration must not end anything later (check at 60 min: nothing changes). App deletion is allowed after the Easy session replaced Hardcore. *(Replacement policy itself changes with ADR-012 / B4.)* |
| B2-07 | Start a 45-min session. Change the time zone (Settings → General → Date & Time, turn off automatic, pick a zone 3 h east). Reopen Flint. | Settings shows a time-zone notice; the shield lifts at the **original absolute** end (45 min after start), not 3 h early/late. |
| B2-08 | Start a 60-min session. Turn off automatic time and move the clock forward 2 h. Reopen Flint. | Current behaviour (until ADR-012): the session may end immediately; Settings records a "clock moved forward" anomaly. Not silent. |
| B2-09 | Start a 60-min session; move the clock back 1 h; reopen Flint. | Settings records a "clock moved back" anomaly; the session keeps its recorded end time. |
| B2-10 | Start a 60-min Hardcore session; reboot the phone; unlock; wait until the end time. | Shield and app-deletion denial persist after reboot and lift at the end; time integrity reports "unverifiable" (no false anomaly). |
| B2-11 | Start a 25-min session from a Focus filter (Focus on), then turn the Focus off. | Block starts with the Focus and ends when it turns off. |
| B2-12 | Start a 30-min Hardcore session; use the Emergency Pass. | Shield and app-deletion denial lift immediately; pass shows as used. |
| B2-13 | Upgrade test: with a session running on the previous build, install this build over it. | The running session still ends at its time (legacy `flint.session` name) and shows correctly in the app. |
| B2-14 | Start a 30-min session at 23:50 (crosses midnight); force-quit Flint. | Shield lifts at 00:20, not 24 h later and not immediately. |
| B2-15 | Start a session and immediately background Flint (registration whose start components are "now"). | The session ends at its end time — the non-repeating interval resolved to today, not tomorrow. |
| B2-16 | Start a 60-min session, change the time zone so iOS fires the end early, keep Flint closed. | The block stays until the original end (the monitor re-registers its end from inside the extension); Settings shows a premature-end notice. Confirms `startMonitoring` works from the DeviceActivityMonitor extension. |
| B2-17 | During a running session, open Flint repeatedly (5×, a few seconds apart). | No visible change; Settings does not accumulate failures (checks whether `DeviceActivityCenter.activities` reports extension-registered activities). |

## B1 — Safari / web restrictions (PR #78)

Settings → Website rules → "Restrict Safari during sessions" on, mode "Limit adult websites",
add `example.org` to "Also block"; "Schedules and Sleep" off unless the step says otherwise.

| ID | Steps | Expected |
| --- | --- | --- |
| B1-01 | Start a 25-min session. In Safari open `example.org`, an adult test site, and try a Private tab. | `example.org` and the adult site are blocked by Screen Time; Private Browsing is unavailable; Settings → Safari → Clear History is disabled. |
| B1-02 | Let B1-01 expire with Flint closed. | All three restrictions lift at the session end. |
| B1-03 | Mode "Allowed sites only", allow `wikipedia.org`; start a session. | Only `wikipedia.org` loads in Safari; everything else is blocked. *(Regression check for the inverted `.specific` mapping fixed in #78.)* |
| B1-04 | With "Schedules and Sleep" on, create a schedule active now (15+ min) with any app; also start a 15-min session. End the session early (Easy). | Safari restrictions stay on (the schedule still owns them) until the schedule window ends. |
| B1-05 | Reverse of B1-04: session running, schedule window ends first. | Restrictions stay on until the session ends. |
| B1-06 | Session running with restrictions; turn the toggle off in Settings → Website rules. | Restrictions lift immediately; the session's app shield stays. |
| B1-07 | Use the Emergency Pass on a Hardcore session with restrictions. | Restrictions lift with the shield. |
| B1-08 | Session running; force-quit Flint; reboot; unlock. | Restrictions still apply until the session end. |
| B1-09 | Open the restricted site in Chrome/Firefox during a session. | Not filtered by this feature (documented Safari-only limit); only picker-token website shields apply. Record actual behaviour. |

## B3 — selection caps, reconciler and health (PRs #82, #87)

| ID | Steps | Expected |
| --- | --- | --- |
| B3-01 | Create a schedule with exactly 50 apps; then try 51. | 50 saves and arms; 51 is refused in the editor with the cap message. |
| B3-02 | Sleep allow list (allow-list mode) with 51 allowed apps. | Refused with the app-exception cap message. **Also record** whether iOS actually enforces a 50-token cap on `.all(except:)` — if not, this limit is stricter than necessary. |
| B3-03 | Upgrade from a build where a 51-app schedule was saved. | After first launch the rule shows "not armed"; nothing from that rule stays shielded (stale store cleared). |
| B3-04 | Enable a schedule, a Time Limit and an Open Limit; force-quit; cold-launch Flint straight into Today (do not open Rules). (PC-62) | All three stay armed; Settings shows the real last-check time and no failures. |
| B3-05 | Settings → health card after a day without opening Flint. | Shows the actual last check time (stale warning when old), never "checked now" (PC-73). |
| B3-06 | Change the device time zone with Flint in the background, then foreground it. | A reconcile runs (Settings check time updates); running sessions keep their absolute end. |
