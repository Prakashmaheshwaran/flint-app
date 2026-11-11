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

## 2026-11-11 PR cases (all NOT RUN)

The sections below cover the PRs opened on 2026-11-11. Each row starts `NOT RUN`, and rows
for an unmerged PR are `BLOCKED` until it merges; the RT rows test behaviour already on main.
Results go in the [consolidated plan](../acceptance/device-acceptance-plan.md) rows D22–D35.
Rows that start from a prior signed build need one signed with the `app.flint.focus`
identifiers (ADR-008 amendment): iOS can't update a build signed under another bundle ID in
place.

### F02 — immediate Allow List session (PR #255; plan D22)

From Today → Start focus, set Session mode to **Allow list**, open "Apps to keep available"
and pick Messages only (not Phone), choose Easy, then Begin, unless a row says otherwise.
**Never place a real emergency call.** Cancel the Emergency SOS countdown before it dials and
use a test number you own. Record the iOS version and every app, screen or control that was
shielded.

| ID / contract | Setup and action | Required observation | Status |
| --- | --- | --- | --- |
| ALS-01 / PC-48, PC-50 | Easy 30-minute Allow List session; force-quit Flint. Open Flint from the Home Screen, from the app switcher and from Spotlight. In Flint, check the Emergency Pass status, then use End early and confirm. | Flint opens without a shield from every entry, and End early ends the session after confirmation. A shield over Flint is a P0 FAIL: capture it, stop, and record it against [#255]'s first open question. | NOT RUN · BLOCKED until [#255] merges |
| ALS-02 / PC-48 | Same session. Run R26-01 checks (a)–(c): open Phone, call the test number and return to the in-call screen from the Home Screen, then start and cancel Emergency SOS. Also open Settings → Screen Time, Control Center and Notification Center. | None is shielded. Messages opens; another everyday app is shielded. If Phone, the in-call screen or SOS is shielded, R-26 moves to option B (see the plan's Hard pre-release decisions). | NOT RUN · BLOCKED until [#255] merges |
| ALS-03 / PC-49 | iOS 18.2 or later; record "not applicable" on older iOS. Choose a third-party default calling app, then repeat ALS-02 checks (a) and (b) with it. Restore Phone as the default afterwards. | The default calling app and its in-call screen are never shielded. | NOT RUN · BLOCKED until [#255] merges |
| ALS-04 / PC-41, PC-50 | Precondition: an Emergency Pass is available; this row consumes it. Start a Hardcore 15-minute Allow List session and force-quit Flint. Repeat ALS-01's three entries, look for End early, then use the Emergency Pass. Repeat the entry with the app PIN on. | Flint opens each time and offers no End early. The pass ends the session and lifts every shield; Phone stays reachable throughout. With the PIN on, the gate's "Emergency Pass & recovery" route works (R01-01). | NOT RUN · BLOCKED until [#255] merges |
| ALS-05 / PC-01 | In the Start sheet pick (a) only an app category, (b) only a website, then (c) Messages plus a category and a website. Start (c); open an app from that category and the website in Safari. | Begin stays disabled for (a) and (b). (c) shows "Allow List · 1 app available"; only Messages stays open; the category app and the website are blocked. After the session, Start focus's block-list selection is unchanged. | NOT RUN · BLOCKED until [#255] merges |
| ALS-06 / PC-04, PC-07 | During an Easy, then a Harder, then a Hardcore Allow List session, start a block-list session from Start focus and through the Start Session shortcut. Then, during a Hardcore Allow List session keeping Messages and one other app, start a longer Hardcore Allow List session keeping Messages only. | Switching between block list and allow list is weakening. Easy asks for confirmation, Harder starts its wait, and Hardcore refuses on every route while the original keeps running. Keeping a subset (Messages only) for longer is accepted. | NOT RUN · BLOCKED until [#255] merges |
| ALS-07 / §4, PC-25 | Open the session's break control; then open a shielded app and read its shield. | The session shows "Breaks aren't available during an Allow List session." No break, wait or charge happens, and the shield offers no break. | NOT RUN · BLOCKED until [#255] merges |
| ALS-08 / PC-58, PC-63 | Let a 15-minute Allow List session end with Flint force-quit and the phone locked. Separately, reboot during a 30-minute one and unlock. While one runs, open Today, Rules and Apps. | It lifts at its end without opening Flint. After the reboot the shield persists and ends at the bounded end, with time marked uncertain. Running surfaces show "Allow List · N apps available" and never list kept apps as resting or blocked. History shows one outcome per session. | NOT RUN · BLOCKED until [#255] merges |
| ALS-09 / C09 | VoiceOver and the largest text size on the Session mode control, the kept-apps row and the running session screen. | "Session mode" with Block list and Allow list is announced with its selected state; every control is reachable and its text is complete. | NOT RUN · BLOCKED until [#255] merges |

### 1.3 / 1.5 — date-specific one-time sessions (PR #257; plan D30)

Create one with Rules → New schedule → **One time**, then a date, a start and an end. The
editor shows the length and "Times use <zone>". iOS accepts 15 minutes to under 24 hours.
Record the saved zone, the planned instants in UTC and the observed shield start and end
separately; an accepted registration is not proof of delivery.

| ID / contract | Setup and action | Required observation | Status |
| --- | --- | --- | --- |
| ONE-01 / PC-14, PC-81 | Author an Easy one-time session 7 days ahead and a Hardcore one 30 or more days ahead, 30 minutes each, each blocking one control app. Don't open Flint again before they start; reboot once in between; use the phone normally. | Each shields at its start and lifts at its end without opening Flint; record the delay. The Rules card reads "Starts <day> <time>", then "<n> left", then "Finished" with its toggle hidden. A start that never arrives is a FAIL; record Settings health. | NOT RUN · BLOCKED until [#257] merges |
| ONE-02 / PC-57 | Turn automatic time zone off and set America/New_York. Author: (i) 2027-03-14 01:30–03:30; (ii) 2027-03-14 02:30–04:00; (iii) 2027-03-14 02:30–03:40; (iv) 2027-11-07 01:30–02:30; (v) 2027-11-07 00:30–01:30; (vi) 2027-11-07 00:30–00:30. Then restore your own zone. On each date, record the actual start and end. | All save America/New_York. (i) lasts 1 h (06:30–07:30 UTC). (ii) The missing 02:30 moves forward: 03:30–04:00 EDT, 30 min. (iii) resolves to 10 min and is refused ("iOS needs at least 15 minutes…"). (iv) The ambiguous start takes the earlier 01:30 EDT: 2 h (05:30–07:30 UTC). (v) The ambiguous end takes the later 01:30 EST: 2 h (04:30–06:30 UTC). (vi) would last 25 h into the next day and is refused with "A one-time session must end within 24 hours of its start." | NOT RUN · BLOCKED until [#257] merges |
| ONE-03 / PC-54–56 | Automatic date and time off. (a) For a one-time session starting in 20 minutes, move the clock forward 2 h, keep Flint closed 5 minutes, then open it; restore automatic time. (b) While one is active, move the clock back 1 h. (c) While one is pending, move the device zone 3 h east. | (a) Nothing shields at the moved time; it starts at the real start, and Settings records a clock notice. Record whether iOS fired early and whether the monitor repaired the registration. (b) It ends at its original end, not later. (c) The card shows the new-zone times; it starts and ends at the original instants. | NOT RUN · BLOCKED until [#257] merges |
| ONE-04 / PC-58, PC-81 | Power off before a one-time session starts and power on after its end. Then wait until the same weekday a week later. | No shield after boot. Rules shows Finished; history has no invented start or completion; nothing happens a week later. | NOT RUN · BLOCKED until [#257] merges |
| ONE-05 / PC-11, PC-13–14 | Pending Hardcore: change its date and times, then delete one. Active Hardcore: shorten it, move its date, extend its end, then force-quit Flint through the end. Finished: delete one and move another to a future date. Try a start in the past. | Pending edits save normally. Shortening or moving an active one is saved for after this occurrence, and extending applies now. The queued change applies at the trusted end without registering a past interval. A finished one can be deleted or moved. A past start is refused with "Choose a start time in the future." | NOT RUN · BLOCKED until [#257] merges |
| ONE-06 / PC-02 | Fill the registration pool (B2-05), then author a one-time session. After one finishes, author another. | With the pool full, the new rule shows an arm failure, never a silent unarmed rule. A finished one-time session frees its slot. | NOT RUN · BLOCKED until [#257] merges |
| ONE-07 / C09 | VoiceOver and AX5 on the editor's Repeats / One time control, date picker, next-day note, length and zone lines, and on the Rules card. | Every control is reachable and complete; dates are read in full. | NOT RUN · BLOCKED until [#257] merges |

### F01 — repeated Pomodoro chain (PR #259; plan D31)

From Today → Start focus, choose **Pomodoro**, set Focus, Rest and Rounds (and Long rest when a
row asks), then Begin Pomodoro. Target A is in the chain's apps; B is blocked by a separate
schedule that stays active throughout. Force-quit Flint after Begin unless a row says
otherwise. By design the Live Activity shows only the chain end, so read phases from the
shield and the widget. Times below are from Begin; record logical and observed times
separately.

| ID / contract | Setup and action | Required observation | Status |
| --- | --- | --- | --- |
| POMO-01 / PC-27, PC-76 | Easy; Focus 5, Rest 2, Rounds 3 (19 min). Phone unlocked; leave A in front, untouched. | A's shield lifts at 5:00 and 12:00, returns at 7:00 and 14:00, and lifts for good at 19:00; record each delay. If focus returns only at rest start + 15 min, or not before the chain ends, record FAIL POMO-01: the rest-end warning was missed. | NOT RUN · BLOCKED until [#259] merges |
| POMO-02 / PC-63, PC-76 | Easy; Focus 5, Rest 5, Rounds 3 (25 min). Lock the phone at once. Unlock at 7:30, open A, lock. Unlock at 10:30 and open A at once, then lock. Unlock at 17:30, open A, lock. Unlock at 20:30 and open A at once. At 26:00 unlock and open Flint. | At 7:30 and 17:30 A opens (rest). At 10:30 and 20:30 A is shielded; if not, keep it in front and record when it re-shields. At 26:00 the chain is over: history has one start and one completed 3-round chain, and Today counts 15 minutes. | NOT RUN · BLOCKED until [#259] merges |
| POMO-03 / PC-76 | Focus 5, Rest 15, Rounds 2 (25 min). Keep the phone locked through the rest's end; unlock at 20:30 and open A at once. | A is shielded (the rest's single end callback). Otherwise record when it re-shields. | NOT RUN · BLOCKED until [#259] merges |
| POMO-04 / PC-27 | Focus 5, Rest 1, Rounds 3, Long rest 5 every 2 rounds (21 min). | The 1-minute rest follows round 1 and the 5-minute long rest follows round 2; record both boundaries. | NOT RUN · BLOCKED until [#259] merges |
| POMO-05 / PC-27, PC-41 | During a rest: check B; try End early on an Easy, a Harder and a Hardcore chain; look for skip, extend or edit. With Website rules on, open a restricted site in Safari. Then use the Emergency Pass during a rest (consumes it). | B stays shielded. Easy ends after confirmation, Harder starts its wait and Hardcore offers no end. There is no skip, extend or edit control. Safari may stay restricted during a rest (known limit, fails closed); record it. The pass ends the chain, and no later round re-shields. | NOT RUN · BLOCKED until [#259] merges |
| POMO-06 / PC-54, PC-58 | Automatic date and time off. During round 1, move the clock forward 1 h with Flint closed, then restore it. During a rest, move it back 30 min with Flint closed. Separately, reboot during round 2 and unlock. | Forward: the rest does not start early. Back: record when focus returns; the rest-end callback can be late by design, and the shield returns at the next callback or launch. After the reboot the chain only moves forward, never restarting a round, and time is marked uncertain. | NOT RUN · BLOCKED until [#259] merges |
| POMO-07 / PC-73, PC-84 | Add the small, medium and lock-screen widgets and watch them through a rest's start and end without opening Flint. Check the Live Activity. | The widget shows "Focus · round k of N" or "Rest · focus resumes <time>". At the phase end it drops the phase line and keeps the session; it never shows a past phase or "State unavailable". The Live Activity shows only the chain end. | NOT RUN · BLOCKED until [#259] merges |
| POMO-08 / PC-07 | Run the Start Pomodoro shortcut with Rounds 4, with Rounds empty and with Rounds 9; then once with Hardcore. | Rounds 4 runs the chain, empty runs one focus round, and 9 is refused with its limits. Hardcore asks for confirmation first (as B4-02). | NOT RUN · BLOCKED until [#259] merges |
| POMO-09 / C09 | VoiceOver and AX5 on the Focus / Pomodoro chips, the steppers, the disclosure and the running phase line. | The chips read "Single focus session" and "Pomodoro focus and rest chain" with their selected state; the disclosure and phase line are complete. | NOT RUN · BLOCKED until [#259] merges |

### F09 — offline legal screens (PR #260; plan D35)

| ID / contract | Setup and action | Required observation | Status |
| --- | --- | --- | --- |
| LEG-01 / D21 | Airplane mode on, Wi-Fi off. You → Legal → Privacy policy; scroll to the end; go back. Open Open-source licenses; scroll to Shippori Mincho; go back. Tap a link in either. | Both open offline and show "Bundled with this version of Flint. Available offline." Links read as "label (url)" text and open nothing; no network prompt appears. | NOT RUN · BLOCKED until [#260] merges |
| LEG-02 / D20 | VoiceOver on. Use the Headings rotor in each document, then swipe through a list and through a license. | Headings are announced as headings. Bullet glyphs are silent and each list item is one element; each license paragraph is its own element; Back is reachable. | NOT RUN · BLOCKED until [#260] merges |
| LEG-03 / D20 | Larger Text at its maximum (AX5) with Bold Text on; open both documents and scroll to their ends. | No truncation, clipping or overlap; the last row scrolls clear of the tab bar. | NOT RUN · BLOCKED until [#260] merges |
| LEG-04 / D21 | Compare the policy's first and last headings with `docs/privacy-policy.md` at the tested commit. Try to select and copy a paragraph. | The text matches. Record whether selection works. | NOT RUN · BLOCKED until [#260] merges |

### F05 — durable break and pass history (PR #267; plan D33)

| ID / contract | Setup and action | Required observation | Status |
| --- | --- | --- | --- |
| BHIST-01 / PC-22, PC-63, PC-84 | Easy 30-minute session. At about 10 minutes take a 5-minute break, force-quit Flint, and stay away until the session ends; then open Flint. | Today, Complete and Insights count 25 minutes. The break appears once, and there is one completion. | NOT RUN · BLOCKED until [#267] merges |
| BHIST-02 / PC-23 | Take a break, tap Resume now after 2 minutes, then relaunch Flint twice. | 2 minutes are subtracted, with no duplicate rows. | NOT RUN · BLOCKED until [#267] merges |
| BHIST-03 / PC-41, PC-44 | Precondition: an Emergency Pass is available; this row consumes it. During a session with a live break, confirm the pass and force-quit Flint at once; relaunch twice. | One pass use and one early end (Emergency Pass). The break ends as recovery. No completion or streak credit. | NOT RUN · BLOCKED until [#267] merges |
| BHIST-04 / PC-59 | Reboot during a break; unlock and open Flint. | The break's end is marked time-uncertain, access ended after restart with no extra credit spent, and no completion is invented. | NOT RUN · BLOCKED until [#267] merges |
| BHIST-05 / §12 | On a prior signed build that has ordinary breaks, take two breaks; update in place and open Flint. | The earlier breaks are delivered once on the first refresh and subtracted; clear and retention rules still apply. | NOT RUN · BLOCKED until [#267] merges |

### Red-team fixes (PRs #262–#264) and device-pending items in the #266 record (plan D25, D26, D28, D29)

The N03, N02 and R04 rows wait for their fix PR. The RT rows test behaviour already on main;
their dated record is in [#266]. "Fixture build" and "fault build" mean an owner-approved
signed debug build with the named hook; without one, record NOT APPLICABLE with the reason.

| ID / contract | Setup and action | Required observation | Status |
| --- | --- | --- | --- |
| N03-01 / PC-28, PC-37 | Fresh install; complete onboarding; add the first Open Limit (Easy, cap 2) on one app and open it. | "2 of 2 credits left today", and one credit spends. The quota is never reported unavailable. | NOT RUN · BLOCKED until [#262] merges |
| N03-02 / PC-37, PC-70 | On a prior signed build, use sessions and schedules but never an Open Limit. Update in place, cold-launch, add the first Open Limit and open its app. | The first credit is available at once, not unavailable until the next reset. | NOT RUN · BLOCKED until [#262] merges |
| N03-03 / PC-36 | On a prior signed build, add an Open Limit (cap 2), spend one credit and delete the limit. Update the same day and add an Open Limit (cap 2) on the same app. | "1 of 2 credits left today": the surviving count decides, and there is no fresh pool. | NOT RUN · BLOCKED until [#262] merges |
| N02-01 / PC-22, PC-65 | Fixture build. Easy session on A+B; take a 5-minute break for A; force-quit Flint; remove the `flint.activeSession` projection with the fixture; stay in A past 300 s. | A re-shields at the break deadline without opening Flint; B stays shielded; nothing is refunded. | NOT RUN · BLOCKED until [#264] merges |
| N02-02 / PC-22, PC-26 | Normal build. Start an Easy session on A+B with the Start Session shortcut. Save a Hardcore schedule's weakening for after its occurrence, timed to end during the break, so its queued change falls due then. Take a break for A, force-quit Flint and stay in A through the deadline. Then start a Hardcore session on A+B during a second break, and run Stop Session during a third. | A re-shields at the deadline (record the delay), and the queued change applies. The Hardcore start shields A and B at once. Stop Session follows the Easy tier, and nothing re-shields A or B after the session ends. | NOT RUN · BLOCKED until [#264] merges |
| R04-01 / PC-05, PC-41, PC-79 | Fault build. On a prior signed build, start legacy session X on A+B; update in place. Start replacement Y on A only and crash right after acceptance, before the native apply. Relaunch after Y's planned end while X still runs. | X is restored, and A and B stay shielded (no picker draft is applied). The Emergency Pass preview is available, and the pass ends only X. Ordinary stop follows X's legacy rights. | NOT RUN · BLOCKED until [#263] merges |
| RT-01 / PC-56–57 (R-07) | On main. On a prior signed build with a recurring schedule running (no time anchor), update in place. Change the device zone, and keep Flint closed through the occurrence's end and the next start. | Each occurrence starts and ends once at its saved-zone instants; any release is bounded; time uncertainty is shown, not hidden. | NOT RUN |
| RT-02 / PC-62 (R-20) | On main. On a prior signed build, start a 15-minute session, then update so the new build's first launch lands within a minute of its end. | Flint never shows idle while the shield is applied; the shield lifts once at the end and is not stuck after the next launch. | NOT RUN |

[#255]: https://github.com/Prakashmaheshwaran/flint-app/pull/255
[#257]: https://github.com/Prakashmaheshwaran/flint-app/pull/257
[#259]: https://github.com/Prakashmaheshwaran/flint-app/pull/259
[#260]: https://github.com/Prakashmaheshwaran/flint-app/pull/260
[#262]: https://github.com/Prakashmaheshwaran/flint-app/pull/262
[#263]: https://github.com/Prakashmaheshwaran/flint-app/pull/263
[#264]: https://github.com/Prakashmaheshwaran/flint-app/pull/264
[#266]: https://github.com/Prakashmaheshwaran/flint-app/pull/266
[#267]: https://github.com/Prakashmaheshwaran/flint-app/pull/267
