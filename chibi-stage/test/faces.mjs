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
const av = process.argv[2] || 'AvatarSample_K.vrm';
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const page = await browser.newPage({ viewport: { width: 400, height: 600 } });
page.on('pageerror', (e) => console.log('[pageerror]', e.message));
await page.goto(`http://localhost:${server.address().port}/index.html`);
await page.evaluate(async (av) => await Chibi.add('a', '/avatars/' + av), av);
const list = ['neutral', 'happy', 'angry', 'sad', 'surprised', 'relaxed', 'speak'];
for (const e of list) {
  await page.evaluate((e) => { if (e === 'speak') { Chibi.emotion('a', 'neutral'); Chibi.speaking('a', true); } else Chibi.emotion('a', e); }, e);
  await page.waitForTimeout(900);
  await page.screenshot({ path: `test/out/face-${e}.png`, clip: { x: 130, y: 20, width: 140, height: 120 } });
}
await browser.close(); server.close();

const py = `
from PIL import Image
l=${JSON.stringify(list)}
ims=[Image.open(f'test/out/face-{e}.png') for e in l]
o=Image.new('RGB',(140*len(l),120),'white')
for i,im in enumerate(ims): o.paste(im,(140*i,0))
o=o.resize((o.width*2,o.height*2)); o.save('test/out/faces.png')`;
const { writeFileSync } = await import("node:fs"); const { execSync } = await import("node:child_process");
writeFileSync("test/out/_faces.py", py); execSync("python3 test/out/_faces.py");
