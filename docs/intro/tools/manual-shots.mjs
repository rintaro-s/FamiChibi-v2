import { chromium } from '../../../chibi-stage/node_modules/playwright/index.mjs';
const out = process.argv[2];
const browser = await chromium.launch();
for (const [w, tag] of [[390, 'm'], [1280, 'd']]) {
  const page = await browser.newPage({ viewport: { width: w, height: 900 }, deviceScaleFactor: 1 });
  for (const f of (process.argv[3] || 'index,server,app,avatar,daily,features').split(',')) {
    await page.goto(new URL(`../manual/${f}.html`, import.meta.url).href);
    await page.evaluate(() => document.querySelectorAll('.step').forEach((s) => s.classList.add('in')));
    await page.waitForTimeout(700);
    const ov = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
    console.log(tag, f, 'overflow', ov);
    await page.screenshot({ path: `${out}/${tag}-${f}.png`, fullPage: true });
  }
}
await browser.close();
