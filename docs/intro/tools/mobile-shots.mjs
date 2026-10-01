// Screenshots the intro page on a phone-sized viewport, one per screen height.
// node docs/intro/tools/mobile-shots.mjs [outDir] [width]
import { chromium } from '../../../chibi-stage/node_modules/playwright/index.mjs';
import { fileURLToPath } from 'node:url';
const out = process.argv[2] || '/tmp/famichibi-e2e';
const W = +(process.argv[3] || 390);
const url = new URL('../index.html', import.meta.url).href;
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: W, height: 844 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true });
const page = await ctx.newPage();
const errs = []; page.on('pageerror', (e) => errs.push(e.message));
await page.goto(url); await page.waitForTimeout(1200);
const H = await page.evaluate(() => document.body.scrollHeight);
console.log('H', H, 'scrollW', await page.evaluate(() => [document.documentElement.scrollWidth, innerWidth]), errs);
let i = 0;
for (let y = 0; y < H; y += 800) { await page.evaluate((y) => scrollTo(0, y), y); await page.waitForTimeout(900); await page.screenshot({ path: `${out}/m${String(i++).padStart(2, '0')}.png` }); }
console.log('shots', i, fileURLToPath(url));
await browser.close();
