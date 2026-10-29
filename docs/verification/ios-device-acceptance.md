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
| B2-06 | Start a 60-min **Easy** session; immediately start a 30-min Easy session via the Start Session shortcut (confirm the replacement prompt). Wait 30 min with Flint closed. | The new session ends at 30 min. The original 60-min registration must not end anything later (check at 60 min: nothing changes). *(Replacing Hardcore is now refused — see B4-05.)* |
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

## B4 — session tier rights across every entry point (PR #130)
ADR-012 §1–§4: Harder/Hardcore need a set end; Hardcore can't be weakened or ended early (pass or
expiry only); Harder waits 60/300/900 s and only Flint confirms the end; one session at a time.

| ID | Steps | Expected |
| --- | --- | --- |
| B4-01 | Today → Hardcore, 30 min → Begin. | A "Start a Hardcore block?" dialog states it can't be ended early. Cancel starts nothing; Start Hardcore starts it. |
| B4-02 | Run the Start Session shortcut with Strictness = Hardcore. | Shortcuts asks for confirmation first (iOS 18: Flint's text; iOS 16/17: the system prompt — record the text). Declining starts nothing. |
| B4-03 | Personal Automation set to "Run Immediately" running Start Session (Hardcore). | Record whether iOS shows the confirmation or fails the run. It must **never** start Hardcore silently. |
| B4-04 | Harder 60-min session running. Run Stop Session; wait 60 s; run it again. Then in Flint tap End session. (PC-07) | 1st run: "a 1-minute wait is running"; 2nd run: "open Flint to end it" — still running both times. In Flint the wait shows as done and a final confirm ends the session. |
| B4-05 | Hardcore 60-min running; start an Easy 30-min session from the shortcut. (PC-04) | Refused; the Hardcore session and its app-deletion denial keep running to the original end. |
| B4-06 | Hardcore session on apps A+B, 60 min. In Today pick only A, Hardcore 120 min, Begin. (PC-06) | Refused; B stays blocked. |
| B4-07 | Focus filter (Harder, 30 min) attached to a Focus. Turn the Focus on, then off. | Block starts with the Focus and keeps running after it turns off. Flint → Settings → Focus Filter shows "Focus off didn't end the block" with the wait. Ending it in Flint follows the wait + confirm. |
| B4-08 | Focus filter Hardcore 30 min. Check Settings → Focus → the filter's summary, then turn the Focus on and off. (PC-08) | Summary reads "Hardcore · 30 min · can't be ended early"; the Strictness description states each tier's rights. After Focus off the block runs to its end; the refusal shows in Flint's Focus Filter screen. |
| B4-09 | A Focus filter saved by the previous build with Harder and no Minutes. Turn the Focus on. | No block starts; the filter summary says it needs a set length; Flint's Focus Filter screen shows why. |
| B4-10 | Manual Easy session running; turn on a Focus with a Flint filter, then off. (PC-09) | The Focus starts nothing and its off never ends the manual session; the refusal is shown. |
| B4-11 | Harder session: End session → wait screen; force-quit Flint; reopen after 60 s. (PC-21) | The wait was remembered (not restarted); End needs only the final confirm. |
| B4-12 | Harder session: End session → "I'm good, go back"; End session again. (PC-20) | The full 60 s wait starts again; nothing was counted. |

## R-01 — PIN gate keeps the pass and recovery reachable (PR #152, PC-50)

| ID | Steps | Expected |
| --- | --- | --- |
| R01-01 | Settings → turn on the app PIN. Start a 30-min Hardcore block. Leave Flint, return so the PIN gate shows. Tap "Emergency Pass & recovery". | The gate shows only "A Hardcore block is running until <time>", the pass status and the Screen Time route — no session name, apps or other screens; the PIN keypad is dismissed. |
| R01-02 | From R01-01, use the pass (2-second hold, or the VoiceOver action). | The block ends without unlocking Flint; VoiceOver announces it; after unlocking, Today no longer shows the session. |
| R01-03 | With the recovery view open, go Home and open the app switcher. | The switcher snapshot shows the privacy curtain, not the recovery view; reopening shows the PIN gate, recovery closed. |
| R01-04 | VoiceOver + largest accessibility text size on the gate and recovery view. | The entry and every control are reachable and readable; two-finger scrub returns to the PIN. |

## Rule mutations — schedules, Sleep, Time and Open Limits (PR #162)

R-07 / R-21 / R-22 / R-31 / R-35; PC-10–16, PC-18–21, PC-37/62/64–66/70/71.
Record the selected window in its saved zone and the actual callback/shield time separately.
An accepted native registration is not proof of timely callback delivery.

| ID | Steps | Expected |
| --- | --- | --- |
| RULE-01 | For each rule kind and each tier, edit a current occurrence: rename, strengthen, weaken, turn off and delete. Include a Time Limit below quota and an Open Limit during a timed credit. | Rename/strengthen preserve continuity; Easy weakening confirms, Harder weakening waits then confirms, Hardcore weakening/off/delete saves a separate future revision. Unspent budget and a credit do not make a commitment inactive. |
| RULE-02 | Save a Hardcore weakening/off/delete for after an occurrence; force-quit Flint across its end and the next start. Repeat with the host kept closed, then reopen twice. | The old snapshot protects through its end; the monitor applies the queued revision before the adjacent occurrence. Foreground recovery applies it once, without restarting an already registered revision. Record delivery latency and locked-device/resume behavior. |
| RULE-03 | Disable a Hardcore template and edit it; separately edit a future one-shot before it starts. Re-enable the disabled template. | Inactive edits are available. Re-enable requires explicit confirmation of the tier and finite selected window. A one-shot never replays as a recurring rule. |
| RULE-04 | On a Harder rule commit four weakening operations within one occurrence, reopening Flint between request and confirmation; separately cancel and request again. | Waits are 1, 5, 15 and 15 minutes; maturity alone changes nothing. Final confirmation commits once. Cancel spends nothing and restarts the full wait; rename/toggle does not reset committed counts. |
| RULE-05 | Open a confirmation, change the same rule in another process/entry route, then confirm the older preview. | The old revision is refused; no shield or registration is released. Reopen the current saved rule. |
| RULE-06 | Extend or strengthen an active schedule, leave Flint closed through the former end, then through the new end. Repeat with a Time Limit threshold/target revision. | A delayed old activity/event cannot clear or shield the current revision. Schedule protection stays through the new logical end. Native replacement failure preserves the old rule and protection. |
| RULE-07 | Exhaust an Easy or Harder Time Limit, then increase its quota or remove targets. Confirm saving for the next occurrence. Repeat below quota and with a stricter quota. | An exhausted live weakening explains that today's change is unavailable and queues it without changing today's rule or spending a wait. The next day gets the saved new budget and a fresh shield state. Below quota, ordinary tier gates apply; strengthening retains an already exhausted shield. |
| RULE-08 | On a signed debug fixture build, remove only an initialized domain's rules key while a Hardcore rule is active, then cold-launch. Separately load duplicate IDs or malformed/unknown tier/version data. | The whole affected domain reports unavailable; bytes are preserved and existing registrations/shields are not removed. A missing established key never becomes an empty rule list. |
| RULE-09 | Upgrade with an active legacy schedule/Sleep/limit. Inspect a valid old Time Limit without a tier and separately malformed tier data. End the legacy occurrence, then start the next. | Existing rights last only through the captured running occurrence. The valid tierless limit uses its documented old Easy default with a review flag; malformed data is unavailable. Migration does not grant legacy rights again. |
| RULE-10 | Change an Easy schedule from block mode to allow mode and back, with distinct app/category/domain picks. Repeat a Hardcore mode change through a queued revision. | Every owned shield field is replaced, including old app/domain fields and category exceptions; no previous-mode residue survives. Hardcore keeps its current mode until the queued boundary. |

DeviceActivity does not expose a synchronous used-minutes query to this adapter. On iOS 17.4+
native Time Limit replacements use `includesPastActivity`; verify that already used time is
counted after an under-quota revision. Earlier iOS versions refuse a live native replacement
instead of resetting usage. An exhausted budget/target/zone weakening uses RULE-07's future
revision because Flint cannot accurately decide whether the revised live quota is exhausted.
These API and callback boundaries remain **device-unverified** until the rows above are run.

