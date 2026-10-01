// Lists elements that stick out of the viewport horizontally and are not clipped by an ancestor.
import { chromium } from '../../../chibi-stage/node_modules/playwright/index.mjs';
const W = +(process.argv[2] || 390);
const browser = await chromium.launch();
const ctx = await browser.newContext({ viewport: { width: W, height: 844 }, isMobile: true, hasTouch: true, deviceScaleFactor: 2 });
const page = await ctx.newPage();
await page.goto(new URL('../index.html', import.meta.url).href); await page.waitForTimeout(800);
console.log(await page.evaluate(() => {
  const W = document.documentElement.clientWidth, out = [];
  const clipped = (el) => { for (let p = el.parentElement; p && p !== document.body && p !== document.documentElement; p = p.parentElement) { const o = getComputedStyle(p).overflowX; if (o !== 'visible') return true; } return false; };
  for (const el of document.querySelectorAll('body *')) {
    const b = el.getBoundingClientRect();
    if ((b.right > W + 1 || b.left < -1) && b.width && !clipped(el)) out.push(`${Math.round(b.left)}..${Math.round(b.right)} ${el.tagName}.${el.className?.baseVal ?? el.className}`);
  }
  return `clientW=${W} scrollW=${document.documentElement.scrollWidth}\n` + out.slice(0, 20).join('\n');
}));
await browser.close();
