# Flint — Design

Single source of truth for Flint's brand and UI tokens. Both apps mirror these values; when a
token changes here, update the platform themes to match.

## Files

| File | What it is |
|---|---|
| [`tokens.json`](tokens.json) | Ember & Mist palette, alphas, radius, spacing, sizes, typography, motion. The canonical token set. |
| [`ember-mist/`](ember-mist/) | The 16 reference screens and the system board. |
| [`fonts/`](fonts/) | The bundled fonts' licences and how they were prepared. |
| [`logo-mark.svg`](logo-mark.svg) | The bare flint-spark mark (charcoal stone + amber spark). |
| [`app-icon.svg`](app-icon.svg) | App icon (dark rounded tile, light mark, amber spark). |
| [`brand-identity.html`](brand-identity.html) | Original brand board: wordmark, palette, taglines, personality. |

## Ember & Mist

Flint's UI is one dark night palette, identical on iPhone and Android: a night sky, layered
ridges, drifting mist and a single ember. The reference screens are in
[`ember-mist/`](ember-mist/) (open any `.dc.html` board in a browser for the layout and copy);
fonts and their licences are in [`fonts/`](fonts/).

| Token | Hex | Role |
|---|---|---|
| Night | `#0C0E13` | sky, block screen |
| Ground | `#0E0D0F` | app background |
| Sheet | `#151417` | sheets, dialogs |
| Ridge | `#2C2522` | far mountains |
| Dusk | `#4B3322` | horizon glow |
| Bone | `#EDE6DA` | text, light button |
| Mist | `#B7AE9F` | secondary text |
| Stone | `#9C9486` | tertiary, tabs |
| **Ember** | `#EF9F27` | **the one accent** |
| Glow | `#FFE7C2` | orb core |

Type: **Shippori Mincho 400** for display (titles, timers, numbers) and **Hanken Grotesk**
400/500/600 for text, both bundled. Identical on both platforms: four tabs in a floating bar
(Today · Rules · Insights · You), every layout, colours, type, radii, sheets, switches, motion
and copy. Native per platform: Apple's app picker and the iOS block screen's fixed fields,
permission names, the system back gesture.

## Brand ethos

Raw and honest · no paywall, ever · built in the open · anti-corporate · community-first ·
no dark patterns · hard and sharp. The product UI should feel the same: direct, fast, no
manipulation, no guilt loops.

## Where these tokens live in code

- **iOS:** `FlintBrand` in `ios-app/Packages/FlintCore` (colors, shared with the shield extension) and the SwiftUI components in `ios-app/Flint/DesignSystem/`.
- **Android:** `FlintTheme` / `FlintPalette` and the `FlintKit` components in `android-app/core/core-common`.

> No codegen for v1 — tokens are mirrored by hand. If the set grows, add a generator that emits
> a Swift `Color` extension and a Kotlin `Color` file from `tokens.json`.
