# Android continuous evaluation — 2026-10-28

The merged [C2 implementation (#88)](https://github.com/Prakashmaheshwaran/flint-app/pull/88)
blocked a stationary Clock app at schedule and positive-budget boundaries on both detector
paths. Revoking Accessibility transferred an existing block to Path B without opening Flint;
re-enabling it transferred ownership back. Path B's budget case was slow under concurrent
host builds: its first visible shield was **20.608 seconds late**, with a no-shield sample
still present at **+17.033 seconds**. This is an open latency risk, not a few-second pass.

## Build and environment

- Source: `83a30ee097f4b6bd724833ed6f834cc3fa3583db` (integration merge of #88).
- Variant: debug, built/installed/launched with `make android-run AVD=flint-test`.
- APK SHA-256: `543f1e7fbf5c30e6a0c3f848b8e780197d00e2bef061cfbd31653b95c4be9866`.
- AVD: `flint-test`, `emulator-5554`, Android 15 / API 35, Google APIs ARM64;
  4 virtual CPUs, 2 GiB guest RAM, 1080 × 2400, 420 dpi.
- Proof boot ID: `134e4850-bc5b-4dc5-8433-d421758c7cd0`.
- Existing software-rendered configuration: `hw.gpu.enabled=no`; no GPU override or AVD
  configuration edit. All six observations used this same boot.
- Guest timezone: `America/New_York` (UTC−04:00). No clock/timezone changes or usage resets.
- Target: `com.google.android.deskclock/com.android.deskclock.DeskClock`, an ordinary app,
  not a safety-floor package. Usage Access and overlay access were granted throughout.

## Observations

Times below are UTC on October 28. The boundary and samples use the **guest** clock.

| Case | Boundary / command start | First visible window, upper bound | Delay, upper bound | Complete samples | Rendered result |
| --- | --- | --- | --- | ---: | --- |
| Path A schedule | 22:21:00.000 | 22:21:02.807 | +2.807 s | 192 | [SCHEDULE](path-a-schedule.png) |
| Path A positive budget | 22:25:46.929 | 22:25:48.218 | +1.289 s | 84 | [TIME LIMIT](path-a-budget.png) |
| Path B schedule | 22:31:00.000 | 22:31:02.483 | +2.483 s | 118 | [SCHEDULE](path-b-schedule.png) |
| Path B positive budget | 22:38:57.044 | 22:39:17.652 | **+20.608 s** | 17 | [TIME LIMIT](path-b-budget.png) |
| A → B, Accessibility revoked | 22:22:01.937 | 22:22:08.109 | +6.172 s | 3 | [Path B shield](path-a-to-b.png) |
| B → A, Accessibility re-enabled | 22:22:45.127 | 22:22:51.406 | +6.279 s | 4 | [Path A shield](path-b-to-a.png) |

The schedule fixtures started at local minutes 1101 (18:21) and 1111 (18:31), ending eight
minutes later. Their uninterrupted sampled foreground windows began 166.939 s and 122.953 s
before the boundaries, respectively. Clock received no taps or app switches during these
intervals. [Clock before blocking](stationary-clock.png) shows the ordinary foreground app.

For each positive budget, Clock was first paused to read today's already-used time. The
debug receiver then set a positive quota leaving more than two minutes, and Clock resumed
once. This preserves existing device usage rather than resetting it or testing only zero:

| Path | Closed event / daily aggregate baseline | Daily quota | Actual resume (UTC) | Uninterrupted time to exhaustion |
| --- | ---: | ---: | --- | ---: |
| A | 472,675 ms / 472,675 ms | 10 minutes | 22:23:39.604 | 127,325 ms |
| B | 1,176,679 ms / 1,176,679 ms | 22 minutes | 22:36:33.723 | 143,321 ms |

Target-only usage dumps ([A](a-budget-after-usage.txt), [B](b-budget-after-usage.txt))
after each budget shield still showed that resume as the final
activity event: no intervening pause, stop or resume. Sampled unblocked windows cover over
60 seconds on each path, even after diagnostic startup delays. On A, the first post-launch
usage dump preceded the resume event's publication; a later read continued the same attempt.
On B, two usage dumps timed out; a read with a 30-second dumpsys timeout recovered the same
attempt. Neither recovery relaunched Clock or changed its quota. The live daily aggregate
also advanced during the open session on this AVD; this run does **not** prove aggregate
lag. Fake-event unit tests cover the worst case in which the aggregate omits the open interval.

The A → B command returned at 22:22:03.180, so first visibility was +4.929 s after return.
The B → A command returned at 22:22:45.555, giving +5.851 s after return. Flint was not opened
during either handoff. Actual bound-service state, not merely the Settings bit, was checked
outside the timed intervals. Every complete sampled window set had at most one Flint shield, and the
first successful sample had exactly the expected overlay type: `ACCESSIBILITY_OVERLAY`
for A or `APPLICATION_OVERLAY` for B. This does not exclude a transient between samples.

## Measurement and evidence

[observations.json](observations.json) contains each fixture, guest-clock brackets, visible
shield types and Clock visibility for every sample. Each read ran
`date +%s%3N; dumpsys window windows; date +%s%3N` through Orca's exact-device shell route.
The last no-shield read and the first shield read bracket visibility; the reported delay
uses the latter read's ending timestamp, so it is an upper bound, not an internal detector
timestamp. The earliest overlay screenshots sometimes preceded Compose content painting.
The linked PNGs are later screenshots with the complete, visibly correct shield content;
the table does not claim rendered-text latency.

One additional Path B budget window dump timed out partway through. Its timestamps are
retained and marked incomplete in the JSON, with unknown visibility; it is excluded from
the complete-sample count and from presence/absence conclusions. Both the last no-shield
sample at +17.033 s and the first shield sample were complete.

The [trimmed AX captures](settled-ax.json) and screenshots agreed on settled Flint and Clock before the timed Path A and Path B
sequences. AX polling was avoided inside timed intervals because Android UiAutomator can
temporarily affect Accessibility binding. A final Path B shield AX read timed out at 45 s;
its shield text is screenshot-verified, not AX-verified. Diagnostic commands and screenshots
are read-only; no UI input occurred during the measured stationary intervals.

Rules were seeded only through the existing debug-only `SetBlocklistReceiver`; no release
testing path was added. Usage diagnostics used `dumpsys usagestats -c` with a target-package
filter (numeric timestamps), never `--checkin`. Full device inventories, unrelated app
usage and private preferences are omitted from this evidence bundle.

## Performance limitations retained

The initial cold launch produced an input-dispatch ANR while the main thread waited in
`IActivityManager.setServiceForeground` / `Service.startForeground`, called from
`UsageStatsForegroundService.onCreate`. Guest CPU pressure `some avg10` was 85.54%, total
CPU 94%, and memory pressure `some avg10` 25.44%. See the [startup screenshot](startup-anr.png)
and [trimmed main-thread/pressure evidence](startup-anr.txt). Waiting once removed the dialog,
but it returned with the same ANR timestamp; closing that app instance and making one warm
launch settled the UI. The five earlier cases then ran in an explicitly coordinated quiet
window. No root cause is established, and no ANR fix is claimed.

The build semaphore was released before the final Path B budget case. Concurrent builds,
usage-dump timeouts, long sample gaps and frame delays were present. A sample still lacked
a shield at boundary +17.033 s; first visibility was +20.608 s, so sampling uncertainty alone
does not explain away the late block. At the optional-repeat checkpoint another Android
gate held the build slot, another was queued, Gradle used 128.9% CPU and host `memory_pressure`
reported 34% free. No lower-load repeat was attempted. The follow-up service work will add
debug-only query-duration and tick-to-handoff timing to distinguish future bottlenecks.
[Trimmed app logcat](logcat.txt) retains observed frame and monitor-contention warnings;
neither host load nor these warnings establish causation.

Physical devices, OEM background policies, whole-process kill/force-stop resurrection,
locked boot, and live boot/time-change re-evaluation were not tested here. Boot/clock wakeups,
DST/weekday boundaries, cursor retention, ongoing accounting, cache deadlines and liveness
ownership have deterministic JVM coverage. The small screen/permission fixtures here do
not certify every device or establish a worst-case recovery bound.

For the follow-up service device pass, also check failed foreground startup: catching the
initial binder exception does not prove Android cannot later deliver an FGS-timeout
`RemoteServiceException` before `stopSelf` is processed; this platform failure path is unverified.

## Existing implementation validation

At #88 head `861335a45d289cef7d08e75843629c34c805b76a`, 427 targeted local JVM tests passed
(zero failures/errors/skips), plus app debug Kotlin compilation. C2 added 92 tests, including
a 576-case DST/weekday comparison inside the boundary suite. Exact-head hosted results:

- [Android 40776050830](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40776050830):
  passed debug/release APK and AAB builds, release exclusions and 1,650 reported JVM cases
  across variants (normal cache reuse; not 1,650 distinct new tests).
- [Debug smoke 40776050884](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40776050884):
  four device tests passed.
- [Release smoke 40776050738](https://github.com/Prakashmaheshwaran/flint-app/actions/runs/40776050738):
  one device test passed.
- GitGuardian passed. This follow-up changes evidence only; it does not modify the APK.

## Restoration

The test-only blocklist, schedule and Clock limit were returned to effective empty/default
values through separate debug-receiver commands. The newly created legacy preference files
remain, with the Clock limit disabled (`-1` means no limit); no app-data reset was performed.
Accessibility was returned to its original disabled state, and the temporary notification
grant was revoked. Usage Access, overlay access, screen timeout and timezone were unchanged.
The first combined reset exposed the receiver's first-matching-branch behavior, so persisted
values were checked and the remaining fields were restored separately during a cleanup-only
boot. That boot is not used as enforcement proof.

The proof boot was shut down by `make clean-devices`. Orca's cleanup-only boot omitted a
`-port` argument, so the strict ledger incarnation parser correctly refused automatic
shutdown. The lead explicitly authorized a manual ownership review for this one boot:
PID 70339 still had the exact 18:42:03 EDT start, the emulator command named `flint-test`,
`lsof` tied its console/adb ports to 5554/5555, adb identified the same AVD, and the worker
had continuously held the device semaphore since before attach. All conditions matched.
Orca then shut down that exact device; a subsequent check found PID 70339 absent and the
adb device list empty. The use-only ledger claim was released, `make clean-devices` ran
again, and both semaphores were released. No shared emulator or unrelated process was killed.
The [restoration record](restoration.json) summarizes the checks and the limited manual approval.
