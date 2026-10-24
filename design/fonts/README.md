# Bundled fonts

Both apps bundle the same two families so iPhone and Android render identical type.

| Family | Use | Files | Source |
|---|---|---|---|
| Shippori Mincho 400 | Display: titles, timers, numbers | `ShipporiMincho-Regular.ttf` | [google/fonts `ofl/shipporimincho`](https://github.com/google/fonts/tree/main/ofl/shipporimincho) |
| Hanken Grotesk 400/500/600 | Text: body, rows, buttons, eyebrows | `HankenGrotesk-{Regular,Medium,SemiBold}.ttf` | [google/fonts `ofl/hankengrotesk`](https://github.com/google/fonts/tree/main/ofl/hankengrotesk) |

Both are licensed under the SIL Open Font License 1.1 (`*-OFL.txt` here and beside each copy).

## Modifications

- **Shippori Mincho** is subset to Latin, Latin-1 and common punctuation (the upstream file is
  8.7 MB of CJK glyphs), and its digits `0`–`9` are re-spaced to one shared advance width so
  timers never jitter — the font ships no `tnum` feature.
- **Hanken Grotesk** is instanced from the upstream variable font into static 400/500/600
  files (Android's `minSdk` 23 predates variable-font axes). Its digits are already tabular.

Copies live in `ios-app/Flint/Resources/Fonts/` and `android-app/core/core-common/src/main/res/font/`.
