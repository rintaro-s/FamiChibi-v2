import { chromium } from '../../../chibi-stage/node_modules/playwright/index.mjs';
const W = +(process.argv[2] || 1280);
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: W, height: 800 } });
await page.goto(new URL('../index.html', import.meta.url).href); await page.waitForTimeout(800);
console.log(await page.evaluate(() => {
  const W = document.documentElement.clientWidth, out = [];
  for (const el of document.querySelectorAll('body *')) {
    const b = el.getBoundingClientRect();
    if (b.right > W + 1 && b.width) {
      const chain = []; for (let p = el.parentElement; p && p !== document.body; p = p.parentElement) { const o = getComputedStyle(p); if (o.overflowX !== 'visible') chain.push(p.tagName + '.' + p.className + ':' + o.overflowX); }
      out.push(`${Math.round(b.right)} ${el.tagName}.${el.className?.baseVal ?? el.className} pos=${getComputedStyle(el).position} clip=[${chain.join(',')}]`);
    }
  }
  out.sort((a, b) => parseInt(b) - parseInt(a));
  return out.slice(0, 15).join('\n');
}));
await browser.close();
