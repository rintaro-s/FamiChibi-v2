import { chromium } from 'playwright';
import http from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, resolve, extname } from 'node:path';
const ROOT = resolve('dist'), AV = resolve('../assets/avatars');
const server = http.createServer((req, res) => { const u = decodeURIComponent(req.url.split('?')[0]); const f = u.startsWith('/avatars/') ? join(AV, u.slice(9)) : join(ROOT, u === '/' ? 'index.html' : u); if (!existsSync(f)) { res.writeHead(404); res.end(); return; } res.writeHead(200, { 'content-type': extname(f) === '.js' ? 'text/javascript' : extname(f) === '.html' ? 'text/html' : extname(f) === '.css' ? 'text/css' : 'application/octet-stream' }); res.end(readFileSync(f)); }).listen(0);
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 412, height: 260 }, deviceScaleFactor: 2 });
await page.goto(`http://localhost:${server.address().port}/index.html?layout=room&fraction=0.58&tex=512`);
await page.evaluate(async () => {
  Chibi.room({ hour: 15, members: 3 });
  Chibi.presence('imo', { home: false, state: 'moving', label: '学校' });
  await Promise.all([Chibi.add('mama', '/avatars/VRM1_Sample.vrm', { name: '母', color: '#ff7a90' }), Chibi.add('imo', '/avatars/AvatarSample_L.vrm', { name: '妹', color: '#b57bff' }), Chibi.add('ai', '/avatars/AliciaSolid.vrm', { name: 'ちびAI', ai: true })]);
  Chibi.arrangeRow();
  Chibi.deliver('imo', { text: '今から帰る。駅まで迎えに来て！', emotion: 'worried', motion: 'knock', urgent: true, replies: ['今行く！'] });
});
await page.waitForTimeout(1500);
await page.screenshot({ path: 'test/out/visit.png' });
await browser.close(); server.close();
