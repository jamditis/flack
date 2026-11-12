# Brand asset generator

`build_brand.py` draws every Flack brand asset from one set of numbers: the mark's geometry, the palette and the Manrope wordmark. Change a value at the top of the script, run it, and every file below updates together. [docs/BRAND.md](../../docs/BRAND.md) explains the design.

## What it builds

- `brand/`: the app icon, marks, small mark, wordmarks, lockups, README banners (SVG and PNG) and `social-preview.png`.
- `site/`: `favicon.svg`, `apple-touch-icon.png` and `og-image.png`.
- `app/src/main/res/drawable/`: `ic_launcher_background`, `ic_launcher_foreground`, `ic_launcher_monochrome`, `ic_notification` and `ic_splash`.

It writes only into those three folders and refuses any other path. It does not build `site/fonts/`, which holds the Manrope web font copied unchanged from the `@fontsource-variable/manrope@5.3.0` package with its license.

## Run it

From the repository root:

```bash
python3 brand/tools/build_brand.py
```

Use `--no-png` to rebuild only the SVG and XML files, which needs no Node or Chromium. Use `--fonts DIR` to point at a folder that already holds the two Manrope files.

## Requirements

- Python 3.10 or newer with `fonttools`, `brotli`, `uharfbuzz` and `Pillow`:
  `pip install fonttools brotli uharfbuzz Pillow`
- For the PNG files: Node 18 or newer with Playwright and its Chromium, for example
  `npm install -g playwright && npx playwright install chromium`.
- Fonts: without `--fonts`, the script runs `npm pack @fontsource/manrope@5.3.0` once, in a temporary folder, and reads `manrope-latin-600-normal.woff2` and `manrope-latin-800-normal.woff2` from it. This is its only network access. Both files are checked against pinned SHA-256 hashes, and the script stops if either differs.

The script needs no keys or accounts.

## Reproducibility

The SVG and XML output is deterministic. The PNG files are byte-identical when rendered with the same Chromium build. A different Chromium version can change anti-aliasing slightly without changing the design. After a run, review `git diff`, then run the Android build and lint:

```bash
./gradlew lintDebug assembleDebug
```
