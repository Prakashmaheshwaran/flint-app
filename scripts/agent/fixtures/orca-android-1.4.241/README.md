# Captured Orca Android responses

Source: real Orca 1.4.241 calls against the `flint-test` Android 35 Google APIs
ARM64 AVD on 2026-10-28. Full unmodified replies, command argv, UTC timestamps,
wall times and failures are retained in
`docs/verification/workspace-android-live-2026-10-28-evidence/commands.jsonl`
and its referenced stdout files. The live verification report links the UI
postconditions and final cleanup proof.

Fixture normalization only:

- Envelope request/runtime IDs are replaced with stable capture labels.
- Inventory fixtures keep only the captured Android rows; iOS rows are omitted.
- `pidof.json` normalizes the observed numeric PID to `123`, retaining the raw
  string and trailing newline shape.
- `logcat.json` keeps the first three real entries, including the message-only
  buffer header. No PID field is added. Complete logcat replies are in evidence.

`devices-shutdown.json` and `devices-booted.json` preserve the actual result array
and `backend` fields. `attach.json` has backend and no helper PID;
`attach-reused.json` has neither backend nor helper PID. The two error fixtures
preserve the actual early-boot scrcpy and activity-less monkey failure messages.

The selftest adapts serials to its fake port reservation and can override replies
for negative cases. Device commands remain isolated to fake tools. iOS fixture
behavior is unchanged. PID-less Orca logcat must use explicit SDK adb device/PID
filtering; it must never emit the unscoped captured messages as Flint logs.
