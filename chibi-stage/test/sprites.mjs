// Renders transparent sprite sheets for the intro page (deterministic frame stepping).
import { chromium } from 'playwright';
import http from 'node:http';
import { readFileSync, existsSync, mkdirSync } from 'node:fs';
import { join, resolve, extname } from 'node:path';
const ROOT = resolve('dist'), AV = resolve('../assets/avatars'), OUT = resolve('../docs/intro/img/sprites');
mkdirSync(OUT, { recursive: true });
const server = http.createServer((req, res) => { const u = decodeURIComponent(req.url.split('?')[0]); const f = u.startsWith('/avatars/') ? join(AV, u.slice(9)) : join(ROOT, u === '/' ? 'index.html' : u); if (!existsSync(f)) { res.writeHead(404); res.end(); return; } res.writeHead(200, { 'content-type': extname(f) === '.js' ? 'text/javascript' : extname(f) === '.html' ? 'text/html' : extname(f) === '.css' ? 'text/css' : 'application/octet-stream' }); res.end(readFileSync(f)); }).listen(0);
const browser = await chromium.launch({ args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] });
const W = 200, H = 300;
const jobs = [
  // name, avatar, motion, frames, cycle seconds, facing (-1..1), emotion
  ['walk-imo', 'AvatarSample_L.vrm', 'walk', 8, 1 / 0.95, 1, 'happy'],
  ['walk-mama', 'VRM1_Sample.vrm', 'walk', 8, 1 / 0.95, 1, 'happy'],
  ['wave-ai', 'AliciaSolid.vrm', 'wave', 8, 0.571, 0, 'happy'],
  ['cheer-baba', 'AvatarSample_K.vrm', 'cheer', 8, 0.909, 0, 'happy'],
  ['dance-mama', 'VRM1_Sample.vrm', 'dance', 12, 1.818, 0, 'happy'],
  ['knock-imo', 'AvatarSample_L.vrm', 'knock', 8, 0.449, 0, 'surprised'],
  ['sleep-baba', 'AvatarSample_K.vrm', 'sleepSit', 8, 5.71, 0, 'relaxed'],
];
for (const [name, av, motion, n, cycle, facing, emo] of jobs) {
  const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 2 });
  await page.goto(`http://localhost:${server.address().port}/index.html?fraction=0.92&tex=1024`);
  await page.evaluate(async ([av, motion, facing, emo]) => {
    await Chibi.add('a', '/avatars/' + av, {});
    const st = Chibi._stage(); const a = st.actors.get('a');
    Chibi.pause(true);
    a.play(motion, { restart: true }); a.fade = 1; a.prevMotion = null;
    a.facingYaw = a.targetYaw = facing * Math.PI * 0.42;
    a.setEmotion(emo, 0.9); a.blinkTimer = 999;
    for (let i = 0; i < 40; i++) a.update(0.05, st.camera); // settle springs & face
  }, [av, motion, facing, emo]);
  const frames = [];
  for (let i = 0; i < n; i++) {
    await page.evaluate(([i, n, cycle]) => {
      const st = Chibi._stage(); const a = st.actors.get('a');
      const target = (i / n) * cycle;
      // step to the target phase in small increments so spring bones follow naturally
      const steps = 6; const cur = a.motion.t % cycle;
      let dtt = (target - cur + cycle) % cycle; if (dtt < 1e-4) dtt = cycle;
      for (let k = 0; k < steps; k++) { a.update(dtt / steps, st.camera); a.motion.t = a.motion.t % 1000; }
      a.faceState.blink = 0; if (a.vrm.expressionManager.getExpression('blink')) a.vrm.expressionManager.setValue('blink', 0);
      a.vrm.expressionManager.update();
      st.renderer.render(st.scene, st.camera);
    }, [i, n, cycle]);
    const buf = await page.screenshot({ omitBackground: true });
    frames.push(buf);
  }
  const { writeFileSync } = await import('node:fs');
  frames.forEach((b, i) => writeFileSync(`${OUT}/${name}-${i}.png`, b));
  await page.close();
  console.log(name, frames.length);
}
await browser.close(); server.close();
