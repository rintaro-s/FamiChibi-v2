// scroll each manual page like a person (no forced reveal) and report sections that never appeared
import { chromium } from '../../../chibi-stage/node_modules/playwright/index.mjs';
const out = process.argv[2];
const browser = await chromium.launch();
for (const [w, h, tag] of [[390, 760, 'm'], [1280, 800, 'd']]) {
  const page = await browser.newPage({ viewport: { width: w, height: h } });
  for (const f of ['index', 'server', 'app', 'avatar', 'daily', 'features']) {
    await page.goto(new URL(`../manual/${f}.html`, import.meta.url).href);
    const H = await page.evaluate(() => document.body.scrollHeight);
    for (let y = 0; y < H; y += h / 2) { await page.evaluate((y) => scrollTo(0, y), y); await page.waitForTimeout(120); }
    const hidden = await page.evaluate(() => [...document.querySelectorAll('.step')].filter((s) => !s.classList.contains('in')).length);
    console.log(tag, f, 'never shown:', hidden);
    if (f === 'features') { await page.waitForTimeout(900); await page.screenshot({ path: `${out}/rv-${tag}.png`, fullPage: true }); }
  }
}
await browser.close();
