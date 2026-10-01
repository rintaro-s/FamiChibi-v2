import { chromium } from '../../../chibi-stage/node_modules/playwright/index.mjs';
const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1280, height: 800 } });
await page.goto(new URL('../index.html', import.meta.url).href); await page.waitForTimeout(1200);
await page.screenshot({ path: '/tmp/famichibi-e2e/d-hero.png' });
await page.locator('#concept').scrollIntoViewIfNeeded(); await page.evaluate(() => scrollBy(0, 250)); await page.waitForTimeout(1300);
await page.screenshot({ path: '/tmp/famichibi-e2e/d-room.png' });
console.log(await page.evaluate(() => [document.documentElement.scrollWidth, innerWidth]));
await browser.close();
