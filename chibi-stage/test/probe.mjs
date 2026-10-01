import { chromium } from 'playwright';
import http from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';
const ROOT = resolve('dist'), AV = resolve('../assets/avatars');
const server = http.createServer((req, res) => {
  const u = decodeURIComponent(req.url.split('?')[0]);
  const f = u.startsWith('/avatars/') ? join(AV, u.slice(9)) : join(ROOT, u === '/' ? 'index.html' : u);
  if (!existsSync(f)) { res.writeHead(404); res.end(); return; }
  res.end(readFileSync(f));
}).listen(0);
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 200, height: 300 } });
page.on('pageerror', (e) => console.log('[pageerror]', e.message));
await page.goto(`http://localhost:${server.address().port}/index.html`);
await page.evaluate(async () => await Chibi.add('a', '/avatars/AvatarSample_L.vrm'));
const r = await page.evaluate(process.argv[2]);
console.log(JSON.stringify(r, null, 1));
await browser.close(); server.close();
