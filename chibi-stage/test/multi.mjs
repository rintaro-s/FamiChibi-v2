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
  res.writeHead(200, { 'content-type': types[extname(f)] || 'application/octet-stream' });
  res.end(readFileSync(f));
}).listen(0);
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 412, height: 520 }, deviceScaleFactor: 1.5 });
page.on('pageerror', (e) => console.log('[pageerror]', e.message));
page.on('console', (m) => console.log('[page]', m.text()));
await page.goto(`http://localhost:${server.address().port}/index.html?layout=row&fraction=0.6`);
await page.evaluate(async () => {
  document.body.style.setProperty('background', 'linear-gradient(#fdf1e7,#f7d9c4)', 'important');
  await Promise.all([
    Chibi.add('mama', '/avatars/AvatarSample_K.vrm', { name: 'ママ', color: '#ff7a90' }),
    Chibi.add('imouto', '/avatars/AvatarSample_L.vrm', { name: '妹', color: '#7aa7ff' }),
    Chibi.add('ai', '/avatars/AvatarSample_C.vrm', { name: 'ちびAI', color: '#5ec8ff', ai: true }),
  ]);
  Chibi.arrangeRow(['mama', 'imouto', 'ai']);
  Chibi.deliver('mama', { text: 'カレー作っておいたから食べてね🍛 温めるときはラップ外してね', emotion: 'love' });
  Chibi.deliver('imouto', { text: '今から帰る。迎えに来て！雨やばい☔', emotion: 'worried', urgent: true, replies: ['今行く！', '10分待って', '無理'] });
  Chibi.deliver('ai', { text: '8/15はパパ以外空いてるよ。パパには今聞いてるところ！', emotion: 'happy', reasons: ['カレンダー: ママ・妹は予定なし', 'パパは非公開 → 本人に確認中'] });
});
await page.waitForTimeout(1500);
await page.screenshot({ path: 'test/out/multi.png' });
await page.evaluate(() => { Chibi.unsay('mama'); Chibi.unsay('imouto'); Chibi.unsay('ai'); Chibi.party(); });
await page.waitForTimeout(2200);
await page.screenshot({ path: 'test/out/party.png' });
await browser.close(); server.close();
