import { chromium } from 'playwright';
import http from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, resolve, extname } from 'node:path';
const ROOT = resolve('dist'), AV = resolve('../assets/avatars');
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' };
const server = http.createServer((req, res) => {
  const u = decodeURIComponent(req.url.split('?')[0]);
  const f = u.startsWith('/avatars/') ? join(AV, u.slice(9)) : join(ROOT, u === '/' ? 'index.html' : u);
  if (!existsSync(f)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': types[extname(f)] || 'application/octet-stream' }); res.end(readFileSync(f));
}).listen(0);
const [w, h] = (process.env.SIZE || '412x300').split('x').map(Number);
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: w, height: h }, deviceScaleFactor: 2 });
page.on('pageerror', (e) => console.log('[pageerror]', e.message));
await page.goto(`http://localhost:${server.address().port}/index.html?layout=room&fraction=0.6&tex=512`);
const hour = +(process.env.HOUR || 14);
await page.evaluate(async (hour) => {
  const M = [['mama', 'VRM1_Sample.vrm', '母', '#ff7a90'], ['papa', 'AvatarSample_M.vrm', '父', '#ff8a65'], ['imo', 'AvatarSample_L.vrm', '妹', '#b57bff'],
    ['baba', 'AvatarSample_K.vrm', '祖母', '#2ec4b6'], ['ai', 'AliciaSolid.vrm', 'ちびAI', '#5ec8ff']];
  Chibi.room({ hour, members: 5 });
  Chibi.presence('papa', { home: false, state: 'idle', label: '仕事（〜18:00）' });
  Chibi.presence('imo', { home: false, state: 'moving', label: '学校から帰宅中' });
  Chibi.presence('baba', { home: true, state: 'sleep' });
  await Promise.all(M.map(([id, f, n, c]) => Chibi.add(id, '/avatars/' + f, { name: n, color: c, ai: id === 'ai' })));
  Chibi.arrangeRow();
}, hour);
await page.waitForTimeout(2500);
await page.screenshot({ path: 'test/out/room1.png' });
await page.evaluate(() => Chibi.presence('imo', { home: true, state: 'active', text: 'ただいま！' }, true));
await page.waitForTimeout(1800);
await page.screenshot({ path: 'test/out/room2.png' });
await page.waitForTimeout(3000);
await page.screenshot({ path: 'test/out/room3.png' });
await page.evaluate(() => { Chibi.room({ hour: 21.5 }); Chibi.lights(false); });
await page.waitForTimeout(1200);
await page.screenshot({ path: 'test/out/room4.png' });
await browser.close(); server.close();
