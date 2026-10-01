// Mama joins the voice room from the web client (fake mic) and waits for the Android phone to connect.
// Usage: node voice_android.mjs <token> <familyId> [seconds]
import { chromium } from '../../../chibi-stage/node_modules/playwright/index.mjs';
const [, , token, fid, secs = '60'] = process.argv;
const browser = await chromium.launch({ args: ['--use-fake-ui-for-media-stream', '--use-fake-device-for-media-stream', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const ctx = await browser.newContext({ viewport: { width: 1000, height: 700 }, permissions: ['microphone'] });
const page = await ctx.newPage();
page.on('pageerror', (e) => console.log('pageerror', e.message));
await page.addInitScript(([t, f]) => { localStorage.setItem('famichibi.token', t); localStorage.setItem('famichibi.fid', f); }, [token, fid]);
await page.goto('http://localhost:8000/');
await page.waitForSelector('#view-family:not([hidden])', { timeout: 20000 });
await page.evaluate(() => { const f = window.__famichibi.S.families.find((x) => x.id === localStorage.getItem('famichibi.fid')); const c = f.channels.find((x) => x.kind === 'voice'); document.querySelector(`.chan[data-id="${c.id}"]`).click(); });
await page.waitForSelector('#v-join');
await page.click('#v-join');
console.log('web joined');
for (let i = 0; i < +secs / 3; i++) {
  await page.waitForTimeout(3000);
  const st = await page.evaluate(() => [...document.querySelectorAll('.vp')].map((x) => x.textContent.replace(/\s+/g, ' ').trim()));
  console.log(new Date().toISOString().slice(11, 19), JSON.stringify(st));
}
await page.screenshot({ path: '/tmp/famichibi-e2e/voice_web.png' });
await browser.close();
