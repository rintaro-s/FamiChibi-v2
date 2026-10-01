import { chromium } from 'playwright';
import http from 'node:http';
import { readFileSync, existsSync } from 'node:fs';
import { join, resolve, extname } from 'node:path';
const ROOT = resolve('dist'), AV = resolve('../assets/avatars');
const server = http.createServer((req, res) => { const u = decodeURIComponent(req.url.split('?')[0]); const f = u.startsWith('/avatars/') ? join(AV, u.slice(9)) : join(ROOT, u === '/' ? 'index.html' : u); if (!existsSync(f)) { res.writeHead(404); res.end(); return; } res.writeHead(200, { 'content-type': extname(f) === '.js' ? 'text/javascript' : extname(f) === '.html' ? 'text/html' : 'application/octet-stream' }); res.end(readFileSync(f)); }).listen(0);
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 412, height: 300 } });
await page.goto(`http://localhost:${server.address().port}/index.html?layout=room&fraction=0.6&tex=256`);
const log = await page.evaluate(async () => {
  const st = Chibi._stage(); const out = [];
  Chibi.room({ hour: 14, members: 5 }); out.push(['after room', st.room.width, st.camera.position.z.toFixed(2), st.layoutMode]);
  await Chibi.add('a', '/avatars/AvatarSample_K.vrm', { name: 'x' }); out.push(['after add', st.room.width, st.camera.position.z.toFixed(2), st.layoutMode]);
  Chibi.arrangeRow(); out.push(['after arrange', st.room.width, st.camera.position.z.toFixed(2)]);
  return out;
});
console.log(JSON.stringify(log)); await browser.close(); server.close();
