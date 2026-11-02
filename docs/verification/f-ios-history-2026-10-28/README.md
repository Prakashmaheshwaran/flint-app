# iOS focus-history simulator evidence

These screens use **DEBUG-seeded events**, not observed Screen Time activity.
`-FlintUIPreviewHistory sample|complete|empty|unavailable|running` writes labelled
fixture events through the production history-store API into a separate temporary
directory. It compiles out of Release. `-FlintUIPreview` bypasses onboarding for
screenshots; it does not grant Screen Time or prove enforcement.

## Builds and devices

- Complete and Insights: combined build `6ecf0c4`, 255 native tests passed.
- Four regular Today states: UI `08dfde9`; focused time is the sole hero and the
  compact next-session line is inside a solid ground card.
- iPhone 17 AX5: combined build `7d4aa45`, UI `1650524`; all 267 native tests passed.
- Compact Today: UI `9a43e54` source, built on the same combined stack; the app
  build passed after extending the plain background to content heights below
  700 points. This prevents the preset labels crossing the sun on the small device.
- iPhone 17: `1BD4732B-EC1A-4074-8C70-6AC369607185`, regular and largest accessibility
  text size (AX5). Small device: iPhone SE (3rd generation), 375 × 667 points,
  `BAED9A07-CB17-4E18-8D67-F5277131EDE4`, Large and AX5.

The regular iPhone 17 retains the landscape. Accessibility sizes and compact
heights use the plain ground background. The solid history card gives mist text
8.83:1 contrast and bone text 15.63:1 against ground.

## Captured states

| Screenshot | Observed state |
| --- | --- |
| [Today](today-sample.png) | 45m focused today; compact next-session line |
| [Insights](insights-sample.png) | 3h 5m, no-data days, zero day, counts, streak and separate Screen Time section |
| [Complete](complete.png) | Preview · Deep study; 45m session and today totals |
| [Empty](today-empty.png) | No focus sessions yet; no fabricated total |
| [Unavailable](today-unavailable.png) | History unavailable; no fabricated zero |
| [In progress](today-running.png) | 55m including ten in-progress fixture minutes |
| [iPhone 17 AX5 top](today-17-ax5-top.png) | Header and schedule subline remain legible |
| [iPhone 17 AX5 card](today-17-ax5-card.png) | Full focused-time card and wrapping next-session line |
| [iPhone 17 AX5 lower content](today-17-ax5-scrolled.png) | Next-session line, presets and Start focus |
| [Small Large](today-small-large.png) | Card and all preset labels clear against ground |
| [Small AX5 top](today-small-ax5.png) | Header visible; remaining content requires scrolling |
| [Small AX5 after attempted scroll](today-small-ax5-after-gesture.png) | Automation limitation: no movement despite Orca acknowledgement |

The sample week has three completed sessions and one early end over five observed
days. Two earlier days have no data and one observed day has zero minutes; the
partial-week caption identifies the first observed date. Screen Time report data
stays in its report extension, separate from these app-owned focus totals.

## Input and verification limits

Each final screenshot was visually inspected against AX. Every input followed two
fresh AX samples about a second apart showing Flint and an enabled target with a
nonzero frame, plus agreeing pixels. The Insights tap was issued once; its first
post-transition AX call timed out, then pixels and recovered AX proved the destination.
On iPhone 17, one upward scroll and one measured downward adjustment revealed the
whole AX5 card and lower content. The initial single-point gesture syntax was
rejected locally; the supported array syntax was confirmed from the installed CLI
before sending actual input. Settled screenshots replaced animation-time frames.

On the small device, one valid upward gesture returned `ok` but neither pixels
nor AX positions changed. It was not repeated blindly. The AX tree contains the
full focused-time and next-session labels, but **full small-device AX5 visual and
scroll behavior remains unverified**. The retained `today-small-ax5-card-ax-*`
files and gesture response document that failed attempt. Some launch AX files
show loading states; only the later ready samples establish settled state.

All 267 native tests passed at `7d4aa45`, including history/lifecycle, eight history
presentation tests and 22 notification/controller/permission tests. The preceding
R1 run exposed a test setup race between startup recovery and an explicit toggle;
the isolated-toggle test now drains startup first. That failed run is not counted
as a pass. Logs: `/tmp/flint-f-final-device.log` and
`/tmp/flint-f-small-contrast-build.log`; `validation.txt` retains results.

The small device initially timed out while recording its boot incarnation; one
normal-target retry succeeded. Final `make clean-devices` released its verified
helper and deleted the worktree-created simulator after an explicit created claim
with matching live identity. The shared iPhone 17 was preserved when helper/boot
provenance could not be re-established. Dynamic Type was restored to Large before
cleanup, and the device semaphore holder was stopped.

These checks do not establish physical-device monitor completion after app death,
locked-device App Group reads, real Screen Time enforcement or notification delivery.
