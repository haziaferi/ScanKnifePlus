# Launcher icon

The ScanKnife+ launcher icon. It echoes the dart in PaperKnife+'s logo: two identical triangles, mirrored and touching along the
centre line. A cyan scan beam runs along that line, and four crop marks frame an A4 page.

Every colour has one job:

- **Red** (top half) recalls the original app.
- **Steel graphite** (bottom half) is the blade.
- **Cyan** (the beam and the crop marks) is the scanner's light, and is used for nothing else.

## Files

| File | What it is |
|---|---|
| `launcher-icon.vectorcraft` | The design, drawn in VectorCraft 0.12. The whole 108 × 108 artboard is the visible icon. |
| `launcher-icon.svg`, `launcher-icon.png` | VectorCraft's own export and render of it. |
| `make_icon.py` | Writes the app's drawables (below) from the same geometry and colours. |
| `tools/` | The scripts that built the design through VectorCraft's control channel (`vectorcraft --control 7979`) and checked it. |

## Rebuilding the drawables

```bash
python docs/design/launcher/make_icon.py
```

The script writes these files under `app/src/main/res/`:

- `drawable/ic_launcher_background.xml`: the radial navy tile.
- `drawable/ic_launcher_foreground.xml`: the dart, beam and crop marks.
- `drawable/ic_launcher_monochrome.xml`: the themed icon on Android 13 and later. One colour can't draw the beam over the dart, so
  inside the dart the beam is cut out of it.
- `drawable-v26/ic_launcher.xml` and `drawable-v26/ic_launcher_round.xml`: the adaptive icon that combines the three layers above.
- `drawable/ic_launcher_legacy.xml` and `drawable/ic_launcher_legacy_round.xml`: the whole icon in a rounded square and a circle,
  for Android 7.0 and 7.1, which have no adaptive icons. `drawable/ic_launcher.xml` and `drawable/ic_launcher_round.xml` wrap
  them, so `LauncherIconTest` can draw the art by its own name. The test tool only draws on Android 8.0 and up, where the
  adaptive icon replaces those two files.

It also writes `drawable/brand_mark_light.xml` and `drawable/brand_mark_dark.xml`: the mark without its tile, for the wordmark.

- **The light version** has a deeper cyan of the same hue (#008A9E, at least 3:1 on every light theme), darker halves and no glow.
- **The dark version** keeps the icon's colours and glow.
- **Choosing between them:** the app picks one by its own theme setting, not the system's.

Don't edit those files by hand; change `make_icon.py` and run it again.

## Wordmark

"ScanKnife+" is set in the app's own font, Plus Jakarta Sans:

- "Scan" in Medium and "Knife" in ExtraBold, with tracking −0.015 em.
- A smaller raised "+" (62% size) in brand red: #C41E3A on light themes and #F04A66 on OLED. Both clear 4.5:1 on every app
  background.
- The letters take the theme's text colour.

In the app, `Wordmark.kt` draws it: `WordmarkRow` (mark beside the name) on Home, and `WordmarkStack` (mark above it) on About.

`wordmark-light.svg` and `wordmark-dark.svg` are the stacked lockup, outlined and on a transparent ground, for the repository
README. VectorCraft built them with `tools/wordmark.py banner`, after running `python make_icon.py --marks work`.

In the adaptive icon, the artboard is scaled by 72/108 into the safe zone, so no launcher mask cuts into it. Vector drawables have
gradients but no blur. So `make_icon.py` rebuilds each VectorCraft glow from the blur's own profile: a Gaussian blur of each line,
in cyan at 85% opacity.

- The beam's glow is a band whose gradient follows that profile, with radial fades past its two outer ends.
- Each crop mark's glow is a stack of soft strokes, worked out ring by ring.

Pass a path to also get an SVG twin of the full artboard. Rendered at 432 px, the twin matched VectorCraft's render to a mean
difference of 0.5 out of 255, with 99% of pixels within 8.

`LauncherIconTest` renders the real drawables with Robolectric's native graphics. It checks that the beam stays at 3:1 or more at
144 and 48 px, that each part keeps its colour, and that the themed icon's cut-out is there. It writes the renders to
`app/build/reports/launcher-icon/`.
