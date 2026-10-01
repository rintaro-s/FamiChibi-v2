// End-to-end check of the web client. Usage: node web_e2e.mjs [baseUrl] [outDir]
import { chromium } from '../../../chibi-stage/node_modules/playwright/index.mjs';
import { mkdirSync } from 'node:fs';

const BASE = process.argv[2] || 'http://localhost:8765';
const OUT = process.argv[3] || 'out';
mkdirSync(OUT, { recursive: true });
const errors = [];
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader', '--autoplay-policy=no-user-gesture-required'] });

async function run(name, viewport, scale = 1) {
  const ctx = await browser.newContext({ viewport, deviceScaleFactor: scale });
  const page = await ctx.newPage();
  page.on('console', (m) => { if (m.type() === 'error') errors.push(`[${name}] ${m.text()}`); });
  page.on('pageerror', (e) => errors.push(`[${name}] pageerror ${e.message}`));
  const shot = (n) => page.screenshot({ path: `${OUT}/${name}-${n}.png` });
  await page.goto(BASE + '/');
  await page.waitForSelector('#ob-name');
  await page.waitForTimeout(500);
  await shot('01-onboard');
  await page.fill('#ob-name', name === 'desktop' ? 'りんたろう' : 'こころ');
  await page.click('#ob-go');
  await page.waitForSelector('#demo-seed:visible');
  await shot('02-home');
  await page.click('#demo-seed');
  await page.waitForSelector('#view-family:not([hidden])');
  await page.waitForFunction(() => window.Chibi && window.Chibi.ids().length >= 5, null, { timeout: 90000 });
  await page.waitForTimeout(2500);
  await shot('03-family');
  const fid = await page.evaluate(() => window.__famichibi.S.fid);
  const tok = await page.evaluate(() => localStorage.getItem('famichibi.token'));
  const call = (path) => page.evaluate(async ([p, t]) => (await fetch(p, { method: 'POST', headers: { Authorization: 'Bearer ' + t } })).json(), [path, tok]);
  await call(`/api/families/${fid}/demo/pickup`);
  await page.waitForTimeout(2500);
  await shot('04-pickup');
  await call(`/api/families/${fid}/demo/love`);
  await page.waitForTimeout(2000);
  await shot('05-multi-bubbles');
  await call(`/api/families/${fid}/demo/lights`);
  await page.waitForTimeout(3500);
  await shot('06-lights');
  await call(`/api/families/${fid}/demo/schedule`);
  await page.waitForTimeout(3000);
  await shot('07-schedule');
  // open AI channel
  await page.evaluate(() => { const f = window.__famichibi.S.families.find((x) => x.id === window.__famichibi.S.fid); const c = f.channels.find((x) => x.name === 'ちびAI'); document.querySelector(`.chan[data-id="${c.id}"]`)?.click(); });
  await page.waitForTimeout(8000); // virtual papa answers
  await page.evaluate(() => { const d = document.querySelector('.msg.ai details'); if (d) d.open = true; });
  await shot('08-ai-channel');
  await call(`/api/families/${fid}/demo/curry`);
  await page.click('#tabs button[data-tab=memo]');
  await page.fill('#memo-text', '明日16時：洗濯物入れてね');
  await page.waitForTimeout(1200);
  await shot('09-memo');
  await page.waitForTimeout(9000);
  await shot('10-memo-due');
  await page.click('#tabs button[data-tab=cal]');
  await page.waitForTimeout(800);
  await page.evaluate(() => document.querySelector('.cal-cell.today')?.click());
  await page.waitForTimeout(400);
  await shot('11-calendar');
  await page.click('#tabs button[data-tab=task]');
  await page.waitForTimeout(600);
  await shot('12-tasks');
  await page.click('#tabs button[data-tab=home]');
  await page.waitForTimeout(800);
  await shot('13-home');
  await page.click('#tabs button[data-tab=settings]');
  await page.waitForTimeout(1500);
  await page.fill('#i-name', 'よしこ'); await page.fill('#i-nick', '祖母'); await page.click('#i-go');
  await page.waitForTimeout(1200);
  await shot('14-settings');
  await page.click('#tabs button[data-tab=chat]');
  await call(`/api/families/${fid}/demo/birthday`);
  await page.waitForTimeout(9000);
  await shot('15-birthday');
  await page.click('#demo-fab');
  await page.waitForTimeout(600);
  await shot('16-demo-drawer');
  await page.click('#demo-close');
  // voice channel
  await page.evaluate(() => { const f = window.__famichibi.S.families.find((x) => x.id === window.__famichibi.S.fid); const c = f.channels.find((x) => x.kind === 'voice'); document.querySelector(`.chan[data-id="${c.id}"]`)?.click(); });
  await page.waitForTimeout(600);
  await shot('17-voice');
  await page.evaluate(() => { document.documentElement.dataset.theme = 'dark'; });
  await page.evaluate(() => { const f = window.__famichibi.S.families.find((x) => x.id === window.__famichibi.S.fid); const c = f.channels.find((x) => x.kind === 'text'); document.querySelector(`.chan[data-id="${c.id}"]`)?.click(); });
  await page.waitForTimeout(1200);
  await shot('18-dark');
  await ctx.close();
}

await run('desktop', { width: 1280, height: 800 });
await run('mobile', { width: 412, height: 860 }, 2);
await browser.close();
console.log(errors.length ? 'ERRORS:\n' + errors.join('\n') : 'no console errors');
