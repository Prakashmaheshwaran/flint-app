# Native iOS icon provenance

`ios-app/Flint/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png`
is the committed 1024 × 1024 universal iOS icon rendered from
`design/app-icon.svg` on 2026-10-28. Xcode's asset compiler generates device
variants from this universal entry. The host's
`ASSETCATALOG_COMPILER_APPICON_NAME` is `AppIcon`.

The PNG is RGB with no alpha channel. The SVG's rounded backdrop is composited
on the same `#2C2C2A` color to make all four corners opaque; iOS applies its own
icon mask. The flint artwork and source colors are preserved.

Provenance SHA-256:

- SVG: `b61038a9b60cf56562591912598b3ceab893aabfbcb2ce259bea35ecd3c4298a`
- PNG: `b444ecba1144fcfba2084972b935702c0d3db4ecf88d0a4c1656064905508306`

Reproduction used Python 3.14, CairoSVG 2.8.2, Pillow 12.0.0 and the system
Cairo library. The committed PNG is authoritative; renderer/library changes
can change antialiasing or PNG encoding, so inspect and update hashes if
regenerating with another environment. No generator dependencies are needed
for ordinary Xcode builds.

```python
import cairosvg
from PIL import Image

cairosvg.svg2png(
    url="design/app-icon.svg", write_to="/tmp/flint-icon-rgba.png",
    output_width=1024, output_height=1024, background_color="#2C2C2A",
)
with Image.open("/tmp/flint-icon-rgba.png") as image:
    image.convert("RGB").save(
        "ios-app/Flint/Resources/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png",
        optimize=False, compress_level=9,
    )
```

Check the compiled app's `CFBundleIcons` / `CFBundleIcons~ipad` and `Assets.car`
after building. A simulator asset compile is packaging evidence; human review
of the final signed archive and App Store validation are still required.
