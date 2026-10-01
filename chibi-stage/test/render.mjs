// Headless render test: loads the stage, adds a VRM, plays motions and saves screenshots.
import { chromium } from 'playwright';
import http from 'node:http';
import { readFileSync, existsSync, mkdirSync } from 'node:fs';
import { extname, join, resolve } from 'node:path';

const ROOT = resolve('dist');
const AV = resolve('../assets/avatars');
const OUT = process.env.OUT || 'test/out';
mkdirSync(OUT, { recursive: true });
const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.vrm': 'application/octet-stream' };
const server = http.createServer((req, res) => {
  const u = decodeURIComponent(req.url.split('?')[0]);
  const f = u.startsWith('/avatars/') ? join(AV, u.slice(9)) : join(ROOT, u === '/' ? 'index.html' : u);
  if (!existsSync(f)) { res.writeHead(404); res.end(); return; }
  res.writeHead(200, { 'content-type': types[extname(f)] || 'application/octet-stream' });
  res.end(readFileSync(f));
}).listen(0);
const port = server.address().port;

const [,, motions = 'idle', avatar = 'AvatarSample_C.vrm', w = '220', h = '330', query = ''] = process.argv;
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: +w, height: +h }, deviceScaleFactor: 1 });
page.on('console', (m) => console.log('[page]', m.text()));
page.on('pageerror', (e) => console.log('[pageerror]', e.message));
await page.goto(`http://localhost:${port}/index.html?${query}`);
await page.evaluate(() => { document.body.style.background = '#cfe8ff'; });
const ok = await page.evaluate(async (a) => await Chibi.add('a', '/avatars/' + a, { name: 'ママ', color: '#ff7a90' }), avatar);
console.log('loaded', ok);
for (const spec of motions.split(',')) {
  const [m, t = '0.6', extra] = spec.split('@');
  await page.evaluate(([m, extra]) => {
    const a = Chibi._stage().actors.get('a');
    if (extra === 'walkL') Chibi.walk('a', -1);
    else if (extra === 'walkR') Chibi.walk('a', 1);
    else { a.setFacing(0); Chibi.play('a', m, { restart: true }); }
  }, [m, extra]);
  await page.waitForTimeout(parseFloat(t) * 1000);
  await page.screenshot({ path: `${OUT}/${m}${extra ? '-' + extra : ''}.png` });
}
await browser.close();
server.close();
