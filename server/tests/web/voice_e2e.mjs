// Two browsers join the same voice channel and must reach RTCPeerConnection 'connected'.
import { chromium } from '../../../chibi-stage/node_modules/playwright/index.mjs';
const BASE = process.argv[2] || 'http://localhost:8765';
const browser = await chromium.launch({ args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const errors = [];
async function user(name) {
  const ctx = await browser.newContext({ viewport: { width: 1000, height: 700 }, permissions: ['microphone'] });
  const page = await ctx.newPage();
  page.on('pageerror', (e) => errors.push(`${name}: ${e.message}`));
  await page.goto(BASE + '/');
  await page.fill('#ob-name', name); await page.click('#ob-go');
  await page.waitForSelector('#demo-seed:visible');
  return page;
}
const a = await user('A');
await a.fill('#cf-name', 'ボイス家'); await a.click('#cf-go');
await a.waitForSelector('#view-family:not([hidden])');
const code = await a.evaluate(() => window.__famichibi.S.families[0].code);
const b = await user('B');
await b.fill('#jf-code', code); await b.click('#jf-go');
await b.waitForSelector('#view-family:not([hidden])');
for (const p of [a, b]) {
  await p.evaluate(() => { const f = window.__famichibi.S.families[0]; const c = f.channels.find((x) => x.kind === 'voice'); document.querySelector(`.chan[data-id="${c.id}"]`).click(); });
  await p.waitForSelector('#v-join');
  await p.click('#v-join');
}
await a.waitForTimeout(6000);
const state = async (p) => p.evaluate(() => [...document.querySelectorAll('.vp small')].map((x) => x.textContent));
console.log('A sees', await state(a), 'B sees', await state(b));
await a.screenshot({ path: process.argv[3] || 'voice.png' });
await browser.close();
console.log(errors.length ? errors.join('\n') : 'no page errors');
