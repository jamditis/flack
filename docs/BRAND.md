# Flack brand guide

This guide covers the name, the mark, the colors, the type and every brand file in the repository. The palette in `app/src/main/res/values/colors.xml` follows it. For the rules every page and image must meet, see [DESIGN_RULES.md](DESIGN_RULES.md) and [WRITING_RULES.md](WRITING_RULES.md).

Flack is an independent project. It is not made by, affiliated with or endorsed by Slack, Google or Samsung.

## Name

Write **Flack**: one word, capital F, lowercase after. Do not write "FLACK", "FlaCK" or "Flack app" as a name. The package ID `tech.amditis.flack` and the repository name `jamditis/flack` stay lowercase because they are identifiers.

Flack may say that it works with Slack and Android Auto. It must not present itself as a Slack, Google or Samsung product. "Flack for Slack", "Slack Car" and "Slack for Android Auto" are not acceptable names or headlines.

## Copy

- Tagline: "Your messages, riding shotgun."
- Supporting line, for the download page and release notes only: "Listen when you choose. Reply by voice when supported."
- Disclaimer, on every card and banner: "Independent app. Not affiliated with Slack." Longer pages use the full sentence from NOTICE.

Images carry only the name, the tagline and the disclaimer.

## The mark

The mark is a highway with a cloverleaf loop pair. One large loop and one small loop cross at a right angle, and the highway runs straight through. The two loops are messages crossing paths on the road. The opening inside the large loop is a round speech bubble with its corner tail at the crossing.

It is one continuous amber form on night navy. It has two loops, two-part balance and no quadrants, so it cannot be mistaken for a four-part pinwheel or a hash sign. Lane lines (dashed centre lines) appear at 48 px and above and are cut out of the road, so they show whatever is behind the mark.

The small mark keeps the same shape with a thinner road, wider loop openings and no lane lines. Use it from 47 px down to 16 px, for the status bar icon and anywhere one flat color is needed.

### Geometry

All values are in the 108-unit Android adaptive-icon canvas. Radii are measured to the centre of the road.

| Part | Mark | Small mark |
| --- | --- | --- |
| Crossing point | 49.3, 58.7 | 49.6, 58.4 |
| Large loop radius | 14 | 13.6 |
| Small loop radius | 8.5 | 9.6 |
| Road width | 7.6 | 7.0 |
| Lane lines | 1.4 wide, 4 long, 3.4 apart | none |

The large loop sits up and right of the crossing and the small loop down and left. Each loop is a 270-degree arc tangent to both roads. In the launcher icon the loops stay inside the 66 dp safe circle with 2 dp to spare, and the highway runs off both edges of the 108 dp canvas.

### Clear space and minimum size

- Clear space: keep an empty margin on every side equal to half the mark's height.
- Mark with lane lines: 48 px tall or larger.
- Small mark: 16 px to 47 px tall.
- Lockup: 48 px tall or larger. Below that, use the small mark or the wordmark on its own.
- Wordmark: 16 px cap height or larger.

## Color

The night-drive palette. Names in brackets are the Android color resources.

| Color | Hex | Use |
| --- | --- | --- |
| Night (`flack_night`) | #0B1524 | Main dark background, icon background, lane lines |
| Night raised (`flack_night_raised`) | #13233C | Raised surfaces in the dark theme |
| Night surface (`flack_night_surface`) | #182A45 | Cards and containers in the dark theme |
| Night outline (`flack_outline_night`) | #2E4263 | Dividers and outlines in the dark theme |
| Amber (`flack_amber`) | #FFB547 | The mark, Flack's own actions, route lines |
| Deep amber (`flack_amber_deep`) | #8A5300 | Amber accents on light backgrounds |
| Soft amber (`flack_amber_soft`) | #FFDDB0 | Containers, second route line |
| On amber (`flack_on_amber`) | #241600 | Text on amber and soft amber |
| Ink (`flack_ink`) | #0E1A2B | Text and the mark on light backgrounds |
| Paper (`flack_paper`) | #F5F7FB | Main light background, text on night |
| Mist (`flack_mist`) | #E8EEF7 | Body text on night |
| Slate (`flack_slate`) | #4A5B73 | Secondary text on light backgrounds |
| Fog (`flack_fog`) | #A9B6CA | Secondary text on night |
| Signal green (`flack_signal_green`) | #5BD69B | Success and "done" only |
| Signal red (`flack_signal_red`) | #FF7A70 | Errors only |

Use color for its usual meaning. Green means done, red means an error, and amber marks Flack's own actions. Never use green or red as decoration.

### Contrast

WCAG 2.2 contrast ratios for the pairs in use. Normal text needs 4.5:1 and graphics need 3:1.

| Foreground | Background | Ratio | Where |
| --- | --- | --- | --- |
| Paper #F5F7FB | Night #0B1524 | 17.07:1 | Wordmark and headlines on dark |
| Mist #E8EEF7 | Night #0B1524 | 15.70:1 | Body text, dark theme |
| Fog #A9B6CA | Night #0B1524 | 8.92:1 | Disclaimer on dark cards and banner, secondary text |
| Fog #A9B6CA | Night raised #13233C | 7.67:1 | Secondary text on raised surfaces |
| Mist #E8EEF7 | Night surface #182A45 | 12.37:1 | Text on dark cards |
| Ink #0E1A2B | Paper #F5F7FB | 16.30:1 | Wordmark, headlines and the light mark |
| Slate #4A5B73 | Paper #F5F7FB | 6.45:1 | Disclaimer on the light banner, secondary text |
| White #FFFFFF | Deep amber #8A5300 | 6.33:1 | Primary buttons, light theme |
| Deep amber #8A5300 | Paper #F5F7FB | 5.90:1 | Amber accents on light |
| On amber #241600 | Amber #FFB547 | 10.05:1 | Primary buttons, dark theme |
| On amber #241600 | Soft amber #FFDDB0 | 13.63:1 | Text in amber containers |
| Signal green #5BD69B | Night #0B1524 | 10.06:1 | Success, dark theme |
| Signal red #FF7A70 | Night #0B1524 | 7.21:1 | Errors, dark theme |
| Success #167A4C | Paper #F5F7FB | 4.99:1 | Success, light theme |
| Amber #FFB547 | Night #0B1524 | 10.42:1 | The mark (graphic) |

Pairs that fail and must not carry text or the mark: amber on paper (1.64:1) and soft amber on paper (1.21:1). That is why light backgrounds get the ink mark or the app tile, never the amber mark on its own. Soft amber on paper appears only as a decorative route line.

## Type

Manrope is the brand typeface. It is licensed under the SIL Open Font License 1.1.

- The wordmark is Manrope ExtraBold (800) at -0.015 em letter spacing, outlined to paths. It needs no font to display.
- Headlines use Manrope ExtraBold. Supporting text uses Manrope SemiBold (600) or Medium (500).
- All text in the SVG banners is outlined, so it renders the same everywhere.

For the download page, `site/fonts/manrope-latin-wght.woff2` is the variable font (weights 200 to 800, Latin) from the Fontsource package of Manrope 4.504. Its license is `site/fonts/OFL.txt`, which must stay next to the font file. Self-host it rather than loading it from a third-party font service:

```css
@font-face {
  font-family: "Manrope";
  src: url("fonts/manrope-latin-wght.woff2") format("woff2");
  font-weight: 200 800;
  font-display: swap;
}
```

Do not use Inter, Space Grotesk, Geist Mono or an editorial serif anywhere in the brand.

## Route lines

Cards and banners use route lines: wide roads on concentric bends that run off the edge, with one road carried over the others. A strip of background color around each road makes the crossing read as a flyover. Use amber and soft amber on night. On paper, use amber and soft amber as decoration only. Routes always run off the edge, so they never end in a rounded cap.

## Do

- Use the amber mark on night or another dark background, and the ink mark or the app tile on light backgrounds.
- Keep the mark one color. Lane lines are openings, not a second color.
- Use the small mark at 47 px and below, and for single-color uses such as the status bar.
- Pair the README banner variants with the reader's light or dark setting (see below).
- Say "works with Slack" when the context needs it.

## Don't

- Use Slack's logo, its hash mark, its aubergine (#4A154B) or its four-color palette, anywhere.
- Use Google, Android Auto or Samsung logos, or imitate their product styling.
- Make anything that could look like an official Slack, Google or Samsung product, announcement or listing.
- Split the mark into colored quadrants, rotate it into a pinwheel or add the other two loops of a full cloverleaf.
- Recolor the mark outside the palette, add gradients, shadows, outlines or glows, or stretch it.
- Place the amber mark on paper or white.
- Use the lane-line mark below 48 px.

## Design rules

These apply to every image, page and mock in this guide. The full list is in [DESIGN_RULES.md](DESIGN_RULES.md).

- Do not number labels with leading zeros (01, 02, 03).
- Do not set secondary text small or in a low-contrast color. On cards it must be readable at thumbnail size and meet WCAG AA.
- Do not put eyebrow text above a headline, in any form: plain, after a dash, or in a pill or chip with a colored dot.
- Do not put a pill with a glowing dot above a headline.
- Do not italicize or underline one word in a headline.
- Do not use gradient blobs, purple gradients, heavy gradients or glassmorphism. Backgrounds are flat night navy or flat paper.
- Do not stack a box behind a box for a 3D look, add a colored strip along one side of a card, or draw strokes that thin at rounded corners.
- Do not use bento grids, a generic startup look or a tan page with big calls to action.
- Do not use Inter, Space Grotesk, Geist Mono or the trendy editorial serif. Manrope is the brand face.
- Do not use a color against its usual meaning.
- Do not add copy to images beyond the name, the tagline and the disclaimer.

## README banner

Use the two banner variants with the reader's color scheme:

```html
<picture>
  <source media="(prefers-color-scheme: dark)" srcset="brand/readme-banner.svg">
  <img src="brand/readme-banner-light.svg" alt="Flack. Your messages, riding shotgun. Independent app, not affiliated with Slack." width="800">
</picture>
```

The PNG files are the same banners at twice the size, for places that do not accept SVG.

## Android resources

- The launcher icon is an adaptive icon: `ic_launcher_background` (flat night), `ic_launcher_foreground` (the mark with lane lines) and `ic_launcher_monochrome` (Android 13 themed icon, one color, loop openings cut out with evenOdd). It was checked under circle, squircle, rounded-square and teardrop masks.
- `ic_notification` is the small mark as a white silhouette for the status bar and Android Auto.
- `ic_splash` is the splash screen icon. The theme should set `android:windowSplashScreenAnimatedIcon` to `@drawable/ic_splash` with a `flack_night` splash background. Its highway ends inside the splash circle, where the launcher foreground's highway would be cut off by the circle.

## Files

| File | Use |
| --- | --- |
| `brand/flack-icon.svg` | App icon as a rounded square, for documents and store-style uses |
| `brand/flack-mark.svg` | The mark for dark backgrounds (amber, lane lines cut out) |
| `brand/flack-mark-light.svg` | The mark for light backgrounds (ink, lane lines cut out) |
| `brand/flack-mark-small.svg` | Small mark, one color (`currentColor`), for 16 px to 47 px |
| `brand/flack-wordmark.svg` | Wordmark for dark backgrounds (paper) |
| `brand/flack-wordmark-light.svg` | Wordmark for light backgrounds (ink) |
| `brand/flack-lockup.svg` | Mark and wordmark side by side, for dark backgrounds |
| `brand/flack-lockup-light.svg` | App tile and wordmark side by side, for light backgrounds |
| `brand/readme-banner.svg`, `brand/readme-banner.png` | README banner, dark (800 x 200, PNG at 1600 x 400) |
| `brand/readme-banner-light.svg`, `brand/readme-banner-light.png` | README banner, light |
| `brand/social-preview.png` | GitHub social preview (1280 x 640). Upload it in the repository settings |
| `site/favicon.svg` | Browser tab icon, drawn for 16 px |
| `site/apple-touch-icon.png` | Home screen icon for iOS (180 x 180, square; iOS rounds the corners) |
| `site/og-image.png` | Link preview card (1200 x 630) |
| `site/fonts/manrope-latin-wght.woff2`, `site/fonts/OFL.txt` | Manrope for the download page, with its license |
| `app/src/main/res/drawable/ic_launcher_background.xml` | Adaptive icon background |
| `app/src/main/res/drawable/ic_launcher_foreground.xml` | Adaptive icon foreground |
| `app/src/main/res/drawable/ic_launcher_monochrome.xml` | Themed icon layer |
| `app/src/main/res/drawable/ic_notification.xml` | Status bar and Android Auto icon |
| `app/src/main/res/drawable/ic_splash.xml` | Splash screen icon |
| `app/src/main/res/mipmap-anydpi/ic_launcher.xml`, `ic_launcher_round.xml` | Adaptive icon definitions |
| `brand/tools/` | Generator that rebuilds every file above from one geometry source (see its README) |
