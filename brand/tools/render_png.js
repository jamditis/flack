// Renders one SVG to a PNG with headless Chromium. Called by build_brand.py.
// Usage: node render_png.js <input.svg> <output.png> <width> <height> <scale>
const { chromium } = require('playwright');
const fs = require('fs');
const os = require('os');
const path = require('path');

(async () => {
  const [, , input, output, w, h, scale] = process.argv;
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'flack-render-'));
  const page = path.join(dir, 'page.html');
  fs.writeFileSync(page, `<html><body style="margin:0"><img id="i" src="file://${path.resolve(input)}" ` +
    `width="${w}" height="${h}" style="display:block"></body></html>`);
  const browser = await chromium.launch();
  try {
    const tab = await browser.newPage({ viewport: { width: +w, height: +h }, deviceScaleFactor: +(scale || 1) });
    await tab.goto('file://' + page);
    const ok = await tab.evaluate(() => { const i = document.getElementById('i'); return i.complete && i.naturalWidth > 0; });
    if (!ok) throw new Error('Image failed to load: ' + input);
    await tab.screenshot({ path: output, clip: { x: 0, y: 0, width: +w, height: +h } });
  } finally {
    await browser.close();
    fs.rmSync(dir, { recursive: true, force: true });
  }
})().catch((e) => { console.error(e.message); process.exit(1); });
