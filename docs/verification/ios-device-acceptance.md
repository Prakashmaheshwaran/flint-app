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

## R-08 removal guard

All rows are **DEVICE-PENDING**. Run on each supported tested iOS version, recording
model, exact version/build, authorization, Flint commit, actual deletion behavior and callback
latency. Unit/integration tests and CI establish requested settings and ownership only.
The older B2 deletion-denial rows now require this explicit opt-in before a new finite
Hardcore commitment; legacy sessions and open-ended owners no longer request the guard.

| ID / contract | Setup and action | Required observation | Status |
| --- | --- | --- | --- |
| R08-01 / §10 | Open You → Restrict app deletion during Hardcore, cancel the disclosure, then accept it. | Default is off; cancel keeps it off. Disclosure explains device-wide scope, revocable Screen Time permission, finite end, Emergency Pass release, recovery locations and version-dependent behavior. A later opt-in leaves existing commitments unchanged. | DEVICE-PENDING |
| R08-02 / PC-78 | Opt in, then overlap a finite Hardcore session and schedule with different ends; keep Flint closed across each boundary. | App deletion remains restricted after the first owner ends and is released after the last. Record whether this iOS version honors the setting and the release latency. | DEVICE-PENDING |
| R08-03 / PC-46 | During overlap, use Emergency Pass, return to You/recovery, and let a new schedule begin during recovery; then resume. | Guard releases after the pass commits, recovery and OS Screen Time settings remain reachable, new recovery occurrences do not re-arm, and only still-active opted-in owners resume. | DEVICE-PENDING |
| R08-04 / PC-79 | Upgrade while a finite legacy Hardcore session is running; separately upgrade with an open-ended legacy owner. | First successful reconcile clears the old guard; session targets and promised legacy exit rights remain. Later opt-in never retroactively arms either owner. | DEVICE-PENDING |
| R08-05 / §9, §10 | Turn off during a finite Hardcore overlap, relaunch, change time zone/wall time, and reach the last logical end. Repeat across reboot. | Pending off persists; current captures remain through their bounded logical ends, new commitments capture off, and off completes without a new commitment extending it. | DEVICE-PENDING |
| R08-06 / PC-61, PC-77 | Revoke then restore Screen Time permission through iOS Settings on every tested version. | Revocation remains reachable. Status shows Unavailable while unauthorized; Requested/On until reflects configuration only. Record actual deletion denial/release separately, including any iOS version that ignores the setting. | DEVICE-PENDING |
| R08-07 / PC-04 | Request guard off during a guarded session, then try a longer Hardcore replacement through the app, Shortcuts and Focus. Re-enable the guard, then retry; separately retry after expiry or pass recovery. | Pending off refuses replacement with the same explanation on every route, preserving the original guard and deadline. Re-enable cancels pending off and permits an otherwise valid replacement; after expiry/off a new session captures off. Pass and recovery remain reachable. | DEVICE-PENDING |
| R08-08 / §6, §8 | Upgrade with a retained removal guard and a corrupt rule domain; separately interrupt an accepted session installation. Replay a positively committed Emergency Pass recovery while the domain remains unreadable. | Ordinary unknown state retains the guard, potentially past the owner's end; committed pass recovery releases it and retires the legacy setting despite unreadable owner inventory. Unknown pass state never authorizes release. Record actual OS behavior and recovery reachability. | DEVICE-PENDING |

## R-23 — ordinary scoped breaks (phase 2; device-pending)

These CRED-style rows require a signed physical device. Simulator tests cover transaction,
clock, callback and injected-store behavior only. Record the carrier warning time, actual
re-shield time and any delay separately; a callback or durable acceptance is not shield proof.
History emission is a separate integration: this phase persists replayable event intents,
and does not count a Void sink return as a durable append receipt.

| ID / contract | Setup and action | Required observation | Status |
| --- | --- | --- | --- |
| BREAK-01 / PC-18 | Harder session; accept successive breaks, using Resume now between them. | Persisted waits are 60, 300, 900, 900 seconds; final confirmation is required, closing/reopening does not reset the wait, and Resume now does not refund a charge. | device-pending |
| BREAK-02 / PC-19 | Target A covered by two Harder occurrences at counts 0 and 2. | One 900-second wait; both counts increase exactly once after acceptance, including double taps and host death at commit. | device-pending |
| BREAK-03 / PC-22 | Accept a break on A; force-quit Flint and remain in A through its logical deadline. Repeat while locked. | A re-shields at the deadline without opening Flint; record actual delay and missed warning separately. Early or duplicate callbacks must not end the break early. | device-pending |
| BREAK-04 / PC-24 | Session blocks A and B; request A-only through the scoped adapter. Repeat an explicit whole-category selection. | Only A releases; B remains shielded. A whole-category request clearly discloses that scope; unknown category membership refuses access. | device-pending |
| BREAK-05 / PC-26 | A and B have a live break; activate a new Hardcore then Easy cause on A, change a contributor revision/end, or replace the session. | Unauthorized targets re-shield immediately; still-authorized targets keep their original deadline; no count refund or resurrection after removing the new cause. | device-pending |
| BREAK-06 / PC-59, PC-65 | Accept then force-quit/relaunch in the same boot; separately reboot and roll the wall clock back. | Same-boot remaining duration is preserved; reboot closes access and retains the charge, with “Access ended after restart; no extra credit was spent.” | device-pending |
| BREAK-07 / PC-75 | Exhaust/refuse carrier registration, then request a break. Separately interrupt native application after durable acceptance. | Registration refusal says “Access unavailable” with no grant or charge. Application recovery uses the same accepted grant and remaining time, with no extra charge. | device-pending |
| BREAK-08 / §6 | Use Emergency Pass during an ordinary break, then Resume protection. | The pass cancels the ordinary grant, stops its exact carrier, and preserves all charges; it never resurrects after recovery. | device-pending |
| BREAK-09 / PC-72 | Application break overlaps a Safari owner; separately request a domain/category whose filter scope is unprovable. | Application-only scope leaves Safari policy intact; unprovable domain/category access is unavailable with no charge. | device-pending |
| BREAK-10 / C09 | Inspect Easy, Harder and Hardcore session controls with VoiceOver and largest text. | Separate “Take a 5-minute break” and “End early” controls show the exact action/tier preview; denied/unavailable breaks show an explanation without an action, and an active break exposes remaining time and Resume now. | device-pending |
| BREAK-11 / §8 | During a live break, make carrier repair fail; reopen or foreground Flint without replaying the request. | Targets re-shield without a refund, the terminal reason is carrier_failed, health records the failure, and the screen says “Access couldn't be enforced; protection is back”. | device-pending |

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


## Shield Open credits — "Use one credit · 5 minutes" (PR #189)

PC-28–35, PC-75. ADR-012 §5.

How the buttons work:
- The primary "Go back" closes without charging. So does every action other than the secondary button, including iOS 26.4 submenu items.
- Only the secondary "Use one credit · N minutes" button can spend a credit, and it is drawn only when the preview shows an available credit.
- A press refused before commitment charges nothing and returns `.defer`. Examples: the pool ran out, another cause blocks, the state is unavailable, or registration failed.
- A failure after commitment, while releasing, keeps the spent credit. While the grant is retained, the redraw shows it as an active credit. If recovery closes it without a refund, a blocked shield reports the credit counts, never "no credit used".
- `.defer` asks iOS to redraw the shield; whether iOS does so is part of what these rows check.

Known limit: the action extension cannot see which labels iOS drew. A delayed press on an old "Use one credit" label therefore spends the one credit it offered, if a credit is still available at press time. The grant is clipped at press time, so it can be shorter than the old label said.

Apple's configuration caching, button delivery, and which response actually releases the shield are **device-unverified** until these rows are run. This is a hard pre-release gate. If PC-28–38 fail on a signed iPhone, set `FlintOpenCredit.shieldAcceptanceEnabled` back to `false`; the shield then shows "Open credits aren't available yet".

Use an Easy Open Limit with a cap of 2 on one app, unless a row says otherwise.

| ID | Steps | Expected |
| --- | --- | --- |
| CRED-01 | Open the limited app and record the shield. Tap "Go back". Reopen it and tap "Use one credit · 5 minutes". | First shield: "2 of 2 credits left today", primary "Go back", secondary "Use one credit · 5 minutes". "Go back" closes and spends nothing. The credit press spends exactly one credit. A successful accept returns `.close`. Record whether that opens the app directly or closes it so it must be relaunched within the credit window, and how long that takes. If `.defer` would refresh the shield in place and open the app, switch the success response in a one-line follow-up. The credit stays bounded and charged once either way. |
| CRED-02 | During the credit, leave the app and reopen it several times. | No second charge and the deadline does not move. If a shield appears, it reads "Credit active for N more min · no extra credit used" with no secondary button. |
| CRED-03 | Let the credit expire with Flint force-quit. Reopen the app and spend the last credit. Let it expire again. | It re-shields at about five minutes; record the latency. Then "Last credit today". After the last credit: "No credits left today · resets about <time>", where the time is the estimate shown in the device's current time zone. No secondary button. |
| CRED-04 | Leave a "Use one credit" shield on screen. Then press its secondary button in three cases: (a) during a live grant started elsewhere; (b) after that grant expired, with a credit remaining; (c) after the pool ran out. | (a) Replays the grant with no extra charge. (b) Spends exactly the one offered credit (known limit). (c) Spends nothing and returns `.defer`. Record whether iOS redraws after `.defer` and what it shows. |
| CRED-05 | Stale "Go back" across a boundary. Leave an exhausted shield on screen past midnight in the quota zone, then tap "Go back". Repeat with a blocking schedule that ends while its shield is displayed. | "Go back" never spends or opens the app. The next drawn shield shows the new period's credits. |
| CRED-06 | Block the app with, in turn: a focus session, a schedule, an exhausted Time Limit, and an unprovable category rule. Repeat while a spent credit is still live, then again in the same quota period after it has expired. | Each shield names only the kind. With an untouched pool: "A focus session still blocks this app · no credit used" (and similarly for the others), or "Another category rule may still block this app; no credit used". While the spent credit is live, the ending reads "· credit active for N more min" (after "; " for the category rule). After it expires, the ending reads "· 1 of 2 credits left today" (category: "; 1 of 2 credits left today"). No secondary button. Nothing more is spent. |
| CRED-07 | Within 5 minutes of the quota-zone midnight, open the app. Repeat, waiting before you press. | The label reads "· N minutes", rounded down. The access ends at midnight even if the press came late. |
| CRED-08 | Use three apps, each covered by exactly one Open Limit: A Easy with cap 0, B Easy with cap 1, C Hardcore with cap 1. Spend B's and C's credit and let them expire. Then add a second, enabled and armed Easy limit covering C, reopen C, and then turn that Easy limit off or delete it. Precondition: an Emergency Pass is available; this row consumes it and disturbs other pass rows. Last, confirm the pass and reopen C. | A: "This Open Limit allows no credits". B (exhausted Easy): "No credits left today · resets about <time>" with no Emergency Pass mention. C (exhausted Hardcore, only Hardcore covering): the same text plus ". An available Emergency Pass can pause this for up to 60 minutes." If the reset time is uncertain, "the reset time is uncertain" replaces "resets about <time>". While the Easy limit also covers C, reopening C shows no pass sentence. No secondary button on any of them. After the pass, C opens during the 60-minute recovery, and its shield returns when recovery ends. The exhausted text reappears only if C is still exhausted then: a quota reset inside the recovery shows credits instead. Record both latencies, and any subtitle truncation per CRED-10. |
| CRED-09 | Pick a category in an Open Limit, then open an app from it that another rule blocks through that category. | "Open credits work for individual apps — pick apps in Flint". No secondary button. |
| CRED-10 | Turn on VoiceOver and the largest text size on every shield above. | All drawn buttons are reachable and their labels are complete. Record any truncation; Apple owns the layout. |

## R-26 — allow lists and the system apps a person needs (PR #218)

Flint refuses a new or edited allow list that keeps no apps available. iOS gives Flint no
list of the system apps that stay reachable under `.all(except:)` or under an all-category
shield, so R26-01 checks that on a device. **DEVICE-PENDING**: if any R26-01 item is
shielded, R-26 moves to option (B), where Flint adds the needed apps itself.

**Never place a real emergency call.** Cancel the Emergency SOS countdown before it dials.
Use a test number you own for the calls. Restore the default calling app afterwards.

For every R26-01 row, allow Messages and **not** Phone: in the schedule editor, open Apple's picker and select Messages only; for Sleep, save an app group in Block Now with Messages only and choose it under Allowed apps. Run
these checks while the rule's window is active:
(a) open Phone;
(b) call the test number and, during the call, return to the in-call screen from the Home Screen;
(c) start Emergency SOS with the side button and either volume button, held, or with five
side-button presses if that setting is on, then cancel the countdown.
Record the iOS version and every app, screen or control that was shielded.

| ID | Steps | Expected |
| --- | --- | --- |
| R26-01a | Rules → New schedule → Allow list on → open the picker and select Messages only (not Phone). Let the window start. Run checks (a)–(c). | Phone, the in-call screen and the SOS countdown are never shielded. Messages opens; other apps are shielded. |
| R26-01b | Sleep → Full Assist, with the Messages-only group under Allowed apps. Let the night window start. Run checks (a)–(c). | As R26-01a. |
| R26-01c | Rules → New schedule → block list → select every app category and no individual app. Let the window start. Run checks (a)–(c). | As R26-01a. Record which other apps stay reachable. |
| R26-01d | Requires iOS 18.2 or later; record "not applicable" on older iOS. Settings → Apps → Default Apps → Calling: choose a third-party calling app (one that offers it). Repeat checks (a) and (b) under R26-01a and R26-01c using that app as well as Phone. Then restore Phone as the default. | The default calling app and its in-call screen are never shielded. |
| R26-02 | On a build without R-26 (main at b34422e, or integration/v1 at eddc7d9), save a Hardcore schedule with Allow list on and nothing chosen, and enable Sleep Wind Down with Allowed apps "Nothing extra". During the schedule's window, record which apps are shielded. Then install this build over it and cold-launch Flint. | The schedule still shields the same apps during its window and stays enabled; the Sleep windows stay saved. Rules shows, on the schedule row and on the "Sleep Mode" row (and "Morning Assist" if saved), "Needs attention — Choose the apps to keep available, for example Phone and Messages. It keeps running as saved until you change it." Sleep's status row shows its needs-attention note. |
| R26-03 | Continue from R26-02. Open the schedule editor. Then turn the schedule off with its toggle. Delete it. In Sleep, change the bedtime and save; then turn Sleep off. | The editor's Save is disabled and shows "Choose the apps to keep available…". Turning off and deleting follow the ordinary Hardcore rules for an active window (saved for after it) or apply outside it, and never show the choose-apps message. The Sleep bedtime save is refused with "…Save an app group with them in Block Now, then choose it under Allowed apps." Turning Sleep off is not refused for the selection. Turning only one Sleep window off (for example Sleep Assist off while Morning Full Assist stays) still saves the other window, so it asks for allowed apps; record this as expected. |
