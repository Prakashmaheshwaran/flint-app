# iOS focus-history simulator evidence

These screens use **DEBUG-seeded events**, not observed Screen Time activity. The
`-FlintUIPreviewHistory` launch argument writes labelled fixture events through
the production history-store API into a separate temporary directory. It is
compiled out of Release. `-FlintUIPreview` bypasses onboarding for screenshots;
it does not grant Screen Time or prove enforcement.

Capture build: `6ecf0c4c672c77fec530f25b6ac0ac928af1e6a4`, containing the history
UI from `1b2672359a0f4170431d13a10056060428928bb2` and the notification follow-up
stack. Device: iPhone 17 Simulator, UDID
`1BD4732B-EC1A-4074-8C70-6AC369607185`.

Fixture expectations:

- `sample`: 45 minutes today; 3h 5m over the observed five-day range; three
  completed sessions and one early end. Two earlier days have no data; one
  observed day has zero minutes. The partial-week caption identifies the first
  observed date.
- `complete`: Preview · Deep study, 45 focused minutes, and 45 minutes today.
- `empty`: no recorded focus sessions; no fabricated total.
- `unavailable`: unavailable history; no fabricated zero.
- `running`: 55 minutes today including ten minutes from an in-progress fixture.

Screen Time report data remains inside its report extension and is separate from
the app-owned focus section. These captures do not verify physical-device monitor
callbacks, App Group protection while locked, or local notification delivery.

All **255 native tests passed**, including 60 history/lifecycle tests, eight
history presentation tests, 14 notifier tests, three controller-to-notifier
integration tests and three notification presentation tests. The full log is
`/tmp/flint-f-ui-device-retry.log`; `validation.txt` retains the relevant results.

| Screenshot | Observed state |
| --- | --- |
| [Today](today-sample.png) | 45m focused today; existing next-session content retained |
| [Insights](insights-sample.png) | 3h 5m; no-data days, zero day, counts, streak and separate Screen Time section |
| [Complete](complete.png) | Durable fixture completion, session name, 45m session and today totals |
| [Empty](today-empty.png) | No focus sessions yet |
| [Unavailable](today-unavailable.png) | Focus history unavailable |
| [In progress](today-running.png) | 55m and In progress |

Each screenshot was visually inspected and checked against the adjacent AX
JSON. Before the single Insights tap, `today-sample-ax-3.json` and
`today-sample-ax-4.json` showed the same enabled, nonzero target frame. The tap
was issued once. The first post-transition AX request timed out; the screenshot
and subsequent `insights-sample-ax-retry.json` confirmed the destination, with
no repeated tap. Some launch AX samples show transitional/loading states; the
final screenshots and ready AX samples are the settled evidence.

The first run target built successfully but timed out while recording device
ownership. A normal-target retry against the same UDID succeeded. After capture,
`make clean-devices` released the verified helper and the device semaphore holder
was stopped; no unproven shutdown rights were inferred from the first attempt.
