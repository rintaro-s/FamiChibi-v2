import { build } from 'esbuild';
import { cpSync, mkdirSync } from 'node:fs';

const out = 'dist';
mkdirSync(out, { recursive: true });
await build({
  entryPoints: ['src/stage.js'],
  bundle: true,
  format: 'iife',
  minify: true,
  target: ['chrome100'],
  outfile: `${out}/chibi-stage.js`,
  legalComments: 'linked',
});
cpSync('src/index.html', `${out}/index.html`);
cpSync('src/stage.css', `${out}/stage.css`);

// Distribute to the server (web client) and the Android app assets.
for (const dest of ['../server/app/static/stage', '../android/app/src/main/assets/stage']) {
  mkdirSync(dest, { recursive: true });
  cpSync(out, dest, { recursive: true });
}
console.log('built');
