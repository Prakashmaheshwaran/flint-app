# Flint verification evidence

This folder holds dated build/test and device/emulator evidence for claims that go beyond
static review. Keep entries honest: say exactly what hardware or emulator was used, what
commands ran, what passed, and what remains unproven.

## Android

- [`android-pathb-2026-07-08/`](android-pathb-2026-07-08/) — API 35 `flint-test`
  emulator pass for Path B fallback enforcement: foreground service lifecycle, blocklist
  overlay, self stand-down/re-shield, Easy break, and daily Time Limit fallback.
- [`android-openlimits-2026-07-08/`](android-openlimits-2026-07-08/) — API 35 `flint-test`
  emulator pass for Open Limits on Path B: a 2-opens/day limit authored through the real
  UI (Blocklist → editor → DataStore), first open allowed, at-quota `OPEN LIMIT` shield
  with the midnight countdown, stand-down/re-shield, Easy break, and open counts surviving
  process death. Known nuance recorded there: the last allowed open is shielded ~1.8 s
  after launch (at-quota re-check on every poll tick).
