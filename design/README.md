# Flint — Design

Single source of truth for Flint's brand and UI tokens. Both apps mirror these values; when a
token changes here, update the platform themes to match.

## Files

| File | What it is |
|---|---|
| [`tokens.json`](tokens.json) | Palette, semantic light/dark colors, radius, spacing, typography. The canonical token set. |
| [`logo-mark.svg`](logo-mark.svg) | The bare flint-spark mark (charcoal stone + amber spark). |
| [`app-icon.svg`](app-icon.svg) | App icon (dark rounded tile, light mark, amber spark). |
| [`brand-identity.html`](brand-identity.html) | Original brand board: wordmark, palette, taglines, personality. |

## Palette

| Token | Hex | Role |
|---|---|---|
| Flint | `#2C2C2A` | charcoal base / dark surfaces |
| Graphite | `#5F5E5A` | secondary / muted |
| **Spark** | `#EF9F27` | **primary accent** |
| Ember | `#FAC775` | light accent |
| Bronze | `#BA7517` | deep accent / pressed |
| Stone | `#F1EFE8` | light surface |

## Brand ethos

Raw and honest · no paywall, ever · built in the open · anti-corporate · community-first ·
no dark patterns · hard and sharp. The product UI should feel the same: direct, fast, no
manipulation, no guilt loops.

## Where these tokens live in code

- **iOS:** `ios-app/Flint/Resources/` Asset Catalog color sets + a `FlintColors` SwiftUI helper.
- **Android:** `android-app/core/core-common` (or the theme module) Compose `Color` + `Theme`.

> No codegen for v1 — tokens are mirrored by hand. If the set grows, add a generator that emits
> a Swift `Color` extension and a Kotlin `Color` file from `tokens.json`.
