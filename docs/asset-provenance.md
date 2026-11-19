# Asset provenance

This file records the origin and licensing status of bundled artwork that is not covered by
the generated third-party notices (which cover libraries and fonts only).

## Streak flame

| Platform | File | Used by |
| --- | --- | --- |
| Android | `android-app/core/core-common/src/main/res/drawable-nodpi/flint_streak_sheet.png` | `FlintStreakEmber` (frame atlas, 45 frames, 9 x 5 grid of 156 x 187 px) |
| iOS | `ios-app/Flint/Resources/flint_streak_fire.gif` | `FlintStreakEmber` via `StreakGifFrames` (45 frames, 60 ms each) |

- **Origin: first-party artwork.** The flame is generated from Flint's own geometry by
  `design/tools/generate_streak_flame.py`: three lobed tongues defined as cubic curves,
  deformed over 45 frames, filled with the streak palette. It is not traced from, sampled
  from, or recoloured from another product's artwork or emoji set.
- **Licence:** Flint's own work, distributed under the repository licence (`LICENSE`, MIT).
- **Attribution:** none beyond the repository licence; no third-party artwork is bundled.
- **Status: cleared for distribution as first-party work.** The generator is checked in so
  the asset can be reproduced, reviewed and adjusted by the project.
- **Regeneration contract:** 45 frames at 60 ms, 156 x 187 px each, Android atlas in a
  9-column grid. `StreakAtlasTest` and `FocusStreakCopyTests` pin these values, so a
  regenerated asset that drifts will fail the build.
