# Asset provenance

This file records the origin and licensing status of bundled artwork that is not covered by
the generated third-party notices (which cover libraries and fonts only).

## Streak flame

| Platform | File | Used by |
| --- | --- | --- |
| Android | `android-app/core/core-common/src/main/res/drawable/flint_streak_sheet.png` | `FlintStreakEmber` (frame atlas, 45 frames, 9 x 5 grid of 156 x 187 px) |
| iOS | `ios-app/Flint/Resources/flint_streak_fire.gif` | `FlintStreakEmber` via `StreakGifFrames` (45 frames, 60 ms each) |

- **What it is:** a stylised fire emblem, supplied as an animated GIF for the streak
  experience. Both platform assets are the same artwork; the Android PNG is a pre-cropped
  atlas derived from it so the renderer never depends on a GIF decoder.
- **Provenance:** supplied by the project owner for Flint. The artwork is emoji-derived and
  the exact upstream licence has **not** been documented or verified.
- **Status: unverified.** No permission statement or licence text is on file. This is
  recorded here rather than omitted so the gap is explicit and cannot be mistaken for a
  cleared third-party asset.
- **Before distributing outside the current private preview:** confirm the licence for the
  artwork, or replace it with first-party artwork. Replacing it is a visual change and needs
  design sign-off; it is intentionally not part of the streak code consolidation.

Both files are byte-identical apart from format/derivation (the Android atlas is generated
from the same 45 frames). `ios-app/Flint/Resources/flint_streak_fire.gif` is the master copy;
regenerating the Android atlas must preserve 45 frames in a 9-column grid of 156 x 187 px,
which `StreakAtlasTest` enforces.
