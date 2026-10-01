// ChibiStage: renders one or more VRM "chibi" actors with procedural motion, expressions,
// eye/head look-at, blink, lip-sync, speech bubbles and particles.
//
// Designed to run inside an Android WebView overlay (transparent background) and in browsers.
// Public API is exposed on window.Chibi (see bottom of file). Native <-> JS messages:
//   JS -> native : window.ChibiNative.postMessage(JSON)   (Android @JavascriptInterface)
//   native -> JS : window.Chibi.<method>(...) via evaluateJavascript

import * as THREE from 'three';
import { GLTFLoader } from 'three/examples/jsm/loaders/GLTFLoader.js';
import { VRMLoaderPlugin, VRMUtils } from '@pixiv/three-vrm';
import { BONES, MOTIONS, basePose } from './motions.js';
import { Room, Director } from './room.js';

const EMOTIONS = ['happy', 'angry', 'sad', 'relaxed', 'surprised'];
const VISEMES = ['aa', 'ih', 'ou', 'ee', 'oh'];
const tmpQ = new THREE.Quaternion();
const tmpE = new THREE.Euler();
const tmpV = new THREE.Vector3();

function post(type, data = {}) {
  const msg = JSON.stringify({ type, ...data });
  try {
    if (window.ChibiNative && window.ChibiNative.postMessage) window.ChibiNative.postMessage(msg);
  } catch (e) { /* ignore */ }
  window.dispatchEvent(new CustomEvent('chibi', { detail: { type, ...data } }));
}

const smooth = (cur, target, rate, dt) => cur + (target - cur) * (1 - Math.exp(-rate * dt));

// ---------------------------------------------------------------------------------------------
// Actor
// ---------------------------------------------------------------------------------------------
class Actor {
  constructor(stage, id, vrm, opts) {
    this.stage = stage;
    this.id = id;
    this.vrm = vrm;
    this.opts = opts;
    this.name = opts.name || '';
    this.color = opts.color || '#ff8a65';
    this.isAI = !!opts.ai;

    this.root = new THREE.Group(); // position / facing in stage space
    this.pivot = new THREE.Group(); // roll / pitch around the waist (for thrown / carried)
    this.root.add(this.pivot);
    this.pivot.add(vrm.scene);
    stage.scene.add(this.root);

    // VRM0's normalized humanoid uses mirrored axes (it faces -Z): x and z rotations are flipped
    // compared with VRM1. All motions are authored for VRM1 and converted at the end of update().
    this.isVRM0 = vrm.meta && vrm.meta.metaVersion === '0';
    this.bones = {};
    for (const name of BONES) {
      const node = vrm.humanoid.getNormalizedBoneNode(name);
      if (node) this.bones[name] = node;
    }
    // Height of the model: top of the head.
    vrm.scene.updateMatrixWorld(true);
    const head = vrm.humanoid.getNormalizedBoneNode('head');
    const headY = head ? head.getWorldPosition(tmpV).y : 1.4;
    const box = new THREE.Box3().setFromObject(vrm.scene);
    this.height = Math.max(headY + 0.18, Math.min(box.max.y, headY + 0.4));
    this.headY = headY;
    const hipN = vrm.humanoid.getNormalizedBoneNode('leftUpperLeg');
    const kneeN = vrm.humanoid.getNormalizedBoneNode('leftLowerLeg');
    this.hipY = hipN ? hipN.getWorldPosition(tmpV).y : headY * 0.55;
    this.kneeY = kneeN ? kneeN.getWorldPosition(tmpV).y : this.hipY * 0.5;
    this.sitDrop = this.kneeY - this.hipY; // hips go down to knee height
    this.pivot.position.y = this.height * 0.5; // rotate around the middle of the body
    vrm.scene.position.y = -this.height * 0.5;

    this.time = Math.random() * 10;
    this.motion = { name: 'idle', t: 0, opts: {} };
    this.prevMotion = null;
    this.fade = 1; // 0..1 crossfade progress to current motion
    this.fadeDur = 0.35;
    this.queue = [];

    this.targetX = null; // stage-space walking target
    this.walkSpeed = 0.9;
    this.facingYaw = 0; // current yaw
    this.targetYaw = 0;
    this.baseX = 0;

    this.emotion = 'neutral';
    this.emotionWeight = 0;
    this.faceState = {}; // current expression values (smoothed)
    this.emotionTarget = {}; // from setEmotion
    this.blinkTimer = 2 + Math.random() * 3;
    this.blinkT = -1;

    this.speaking = false;
    this.mouthLevel = 0; // external lip-sync amplitude 0..1 (from native audio)
    this.mouthExternalUntil = 0;
    this.viseme = 'aa';
    this.visemeTimer = 0;

    this.lookTarget = new THREE.Object3D();
    stage.scene.add(this.lookTarget);
    if (vrm.lookAt) vrm.lookAt.target = this.lookTarget;
    this.lookWeight = 1;
    this.headLook = { yaw: 0, pitch: 0 };

    this.extraRoot = { roll: 0, pitch: 0, y: 0 };
    this.velX = 0;
    this.carried = false;

    this.springDefaults = [];
    if (vrm.springBoneManager) {
      for (const j of vrm.springBoneManager.joints) {
        this.springDefaults.push({ j, dir: j.settings.gravityDir.clone(), power: j.settings.gravityPower });
      }
    }

    this.aura = null;
    if (this.isAI) this._addAura();
  }

  _addAura() {
    // Soft glowing halo ring above the head for the AI chibi.
    const g = new THREE.TorusGeometry(0.11, 0.012, 12, 48);
    const m = new THREE.MeshBasicMaterial({ color: 0x8fd3ff, transparent: true, opacity: 0.9 });
    const ring = new THREE.Mesh(g, m);
    ring.rotation.x = Math.PI / 2;
    const head = this.vrm.humanoid.getNormalizedBoneNode('head');
    ring.position.set(0, 0.28, 0);
    (head || this.vrm.scene).add(ring);
    this.aura = ring;
  }

  get x() { return this.root.position.x; }

  play(name, opts = {}) {
    const def = MOTIONS[name];
    if (!def) return false;
    if (this.motion && this.motion.name === name && def.loop && !opts.restart) {
      this.motion.opts = opts;
      return true;
    }
    this.prevMotion = this.motion;
    this.prevMotionFade = this.fade;
    this.motion = { name, t: 0, opts };
    this.fade = 0;
    this.fadeDur = opts.fade ?? 0.3;
    return true;
  }

  // Queue a sequence of motions: [{motion:'wave'}, {motion:'nod'}, ...]
  sequence(list) {
    this.queue = list.slice();
    this._next();
  }

  _next() {
    const item = this.queue.shift();
    if (!item) {
      this.play(this.speaking ? 'talk' : 'idle');
      return;
    }
    this.play(item.motion, item);
  }

  setEmotion(emotion, weight = 1) {
    this.emotion = emotion || 'neutral';
    this.emotionTarget = {};
    if (EMOTIONS.includes(this.emotion)) this.emotionTarget[this.emotion] = weight;
  }

  setFacing(yaw) { this.targetYaw = yaw; }

  walkTo(x, speed) {
    this.targetX = x;
    if (speed) this.walkSpeed = speed;
  }

  // Spring bones do not know about the overlay window moving on screen, so we fake inertia by
  // bending gravity opposite to the acceleration of the window.
  setInertia(ax, ay) {
    const mag = Math.hypot(ax, ay);
    for (const d of this.springDefaults) {
      if (mag < 1e-3) {
        d.j.settings.gravityDir.copy(d.dir);
        d.j.settings.gravityPower = d.power;
      } else {
        // screen px/s^2 -> rough world units; screen y is down.
        const k = Math.min(1, mag / 6000);
        tmpV.set(-ax / mag * k, (ay / mag) * k - (1 - k), 0).normalize();
        d.j.settings.gravityDir.copy(tmpV);
        d.j.settings.gravityPower = d.power + k * 1.5;
      }
    }
  }

  update(dt, camera) {
    this.time += dt;
    const m = this.motion;
    m.t += dt;
    const def = MOTIONS[m.name];
    const dur = m.opts.duration ?? (def.loop ? null : def.duration);
    if (dur != null && m.t >= dur) {
      this._next();
    }
    this.fade = Math.min(1, this.fade + dt / this.fadeDur);

    // Stage-space walking.
    if (this.targetX !== null) {
      const dx = this.targetX - this.root.position.x;
      if (Math.abs(dx) < 0.02) {
        this.root.position.x = this.targetX;
        this.targetX = null;
        this.targetYaw = 0;
        if (this.motion.name === 'walk' || this.motion.name === 'run') this._next();
        post('arrived', { id: this.id });
        if (this.onArrive) { const f = this.onArrive; this.onArrive = null; f(); }
      } else {
        const dir = Math.sign(dx);
        this.root.position.x += dir * Math.min(Math.abs(dx), this.walkSpeed * dt);
        this.targetYaw = dir * Math.PI * 0.42;
        if (this.motion.name !== 'walk' && this.motion.name !== 'run') this.play('walk');
      }
    }
    this.facingYaw = smooth(this.facingYaw, this.targetYaw, 8, dt);

    const ctx = { facingYaw: 0, velX: this.velX, energy: 1, sitDrop: this.sitDrop };
    const base = basePose(this.time, ctx);
    const cur = def.fn(m.t, ctx) || {};
    let prev = null;
    if (this.prevMotion && this.fade < 1) {
      const pdef = MOTIONS[this.prevMotion.name];
      this.prevMotion.t += dt;
      prev = pdef.fn(this.prevMotion.t, ctx) || {};
    }
    const w = this.fade * this.fade * (3 - 2 * this.fade);

    // Bones: base -> prev -> current (slerp)
    for (const name of BONES) {
      const node = this.bones[name];
      if (!node) continue;
      const bb = base.bones[name] || [0, 0, 0];
      const pv = prev && prev.bones && prev.bones[name] ? prev.bones[name] : bb;
      const cv = cur.bones && cur.bones[name] ? cur.bones[name] : bb;
      tmpE.set(pv[0], pv[1], pv[2], 'XYZ');
      node.quaternion.setFromEuler(tmpE);
      tmpE.set(cv[0], cv[1], cv[2], 'XYZ');
      tmpQ.setFromEuler(tmpE);
      node.quaternion.slerp(tmpQ, prev ? w : 1);
    }

    // Root transforms
    const lerp = (a, b) => a + (b - a) * (prev ? w : 1);
    const pr = (prev && prev.root) || {};
    const cr = cur.root || {};
    const rootY = lerp(pr.y ?? 0, cr.y ?? 0);
    const rootYaw = lerp(pr.yaw ?? 0, cr.yaw ?? 0);
    const roll = lerp(pr.roll ?? 0, cr.roll ?? 0);
    this.pivot.position.y = this.height * 0.5 + rootY;
    this.pivot.rotation.set(0, 0, roll);
    this.root.rotation.y = this.facingYaw + rootYaw;
    if (this.aura) {
      this.aura.rotation.z += dt * 1.5;
      this.aura.position.y = 0.28 + Math.sin(this.time * 2) * 0.015;
    }

    // Look at: camera by default. Head follows partly, eyes via VRM lookAt.
    const wantsLook = (cur.look || this.motion.name === 'idle' || this.motion.name === 'talk') && !this.carried;
    this.lookWeight = smooth(this.lookWeight, wantsLook ? 1 : 0, 4, dt);
    const lookPos = this.stage.lookPoint || camera.position;
    this.lookTarget.position.copy(lookPos);
    const head = this.bones.head;
    if (head) {
      head.getWorldPosition(tmpV);
      const d = new THREE.Vector3().subVectors(lookPos, tmpV);
      // into actor space (undo root yaw)
      d.applyAxisAngle(new THREE.Vector3(0, 1, 0), -this.root.rotation.y);
      const yaw = Math.atan2(d.x, d.z);
      const pitch = -Math.atan2(d.y, Math.hypot(d.x, d.z));
      const clampYaw = Math.max(-0.7, Math.min(0.7, yaw));
      const clampPitch = Math.max(-0.35, Math.min(0.35, pitch));
      this.headLook.yaw = smooth(this.headLook.yaw, clampYaw * this.lookWeight, 6, dt);
      this.headLook.pitch = smooth(this.headLook.pitch, clampPitch * this.lookWeight, 6, dt);
      tmpE.set(this.headLook.pitch * 0.5, this.headLook.yaw * 0.55, 0, 'XYZ');
      tmpQ.setFromEuler(tmpE);
      head.quaternion.premultiply(tmpQ);
      if (this.bones.neck) {
        tmpE.set(this.headLook.pitch * 0.25, this.headLook.yaw * 0.3, 0, 'XYZ');
        tmpQ.setFromEuler(tmpE);
        this.bones.neck.quaternion.premultiply(tmpQ);
      }
    }

    if (this.isVRM0) {
      for (const name of BONES) {
        const q = this.bones[name] && this.bones[name].quaternion;
        if (q) { q.x = -q.x; q.z = -q.z; }
      }
    }
    this._updateFace(dt, cur, prev, w);
    this.vrm.update(dt);
  }

  _updateFace(dt, cur, prev, w) {
    const em = this.vrm.expressionManager;
    if (!em) return;
    const target = {};
    // emotion from message
    for (const k of EMOTIONS) target[k] = this.emotionTarget[k] || 0;
    // motion face overrides/adds
    const cf = cur.face || {};
    const pf = (prev && prev.face) || {};
    for (const k of new Set([...Object.keys(cf), ...Object.keys(pf)])) {
      const v = (pf[k] || 0) * (prev ? 1 - w : 0) + (cf[k] || 0) * (prev ? w : 1);
      target[k] = Math.max(target[k] || 0, v);
    }
    // Keep a single dominant emotion to avoid ugly mixes.
    let best = null, bestV = 0;
    for (const k of EMOTIONS) if ((target[k] || 0) > bestV) { best = k; bestV = target[k]; }
    for (const k of EMOTIONS) if (k !== best) target[k] = 0;

    // Blink
    this.blinkTimer -= dt;
    if (this.blinkTimer <= 0 && this.blinkT < 0) { this.blinkT = 0; this.blinkTimer = 2 + Math.random() * 4; }
    let blink = 0;
    if (this.blinkT >= 0) {
      this.blinkT += dt;
      const bt = this.blinkT / 0.16;
      blink = bt < 0.5 ? bt * 2 : Math.max(0, 2 - bt * 2);
      if (bt >= 1) this.blinkT = -1;
    }
    // happy/surprised close/open eyes themselves
    if ((target.happy || 0) > 0.5 || (target.surprised || 0) > 0.5) blink = 0;
    target.blink = Math.max(blink, target.blink || 0);

    // Lip sync
    const now = performance.now();
    let mouth = 0;
    if (now < this.mouthExternalUntil) {
      mouth = this.mouthLevel;
    } else if (this.speaking) {
      this.visemeTimer -= dt;
      if (this.visemeTimer <= 0) {
        this.visemeTimer = 0.08 + Math.random() * 0.1;
        this.viseme = VISEMES[(Math.random() * VISEMES.length) | 0];
        this.mouthGoal = Math.random() < 0.15 ? 0.05 : 0.45 + Math.random() * 0.5;
      }
      mouth = this.mouthGoal ?? 0.5;
    }
    for (const v of VISEMES) target[v] = Math.max(target[v] || 0, v === this.viseme ? mouth : 0);

    for (const [k, v] of Object.entries(target)) {
      const rate = k === 'blink' ? 60 : VISEMES.includes(k) ? 22 : 7;
      const c = smooth(this.faceState[k] || 0, v, rate, dt);
      this.faceState[k] = c;
      if (em.getExpression(k)) em.setValue(k, Math.min(1, Math.max(0, c)));
    }
  }

  headScreenPos(camera, width, height) {
    const head = this.bones.head;
    if (!head) return null;
    head.getWorldPosition(tmpV);
    tmpV.y += 0.2;
    tmpV.project(camera);
    return { x: (tmpV.x * 0.5 + 0.5) * width, y: (-tmpV.y * 0.5 + 0.5) * height };
  }

  dispose() {
    this.stage.scene.remove(this.root);
    this.stage.scene.remove(this.lookTarget);
    VRMUtils.deepDispose(this.vrm.scene);
  }
}

// ---------------------------------------------------------------------------------------------
// Particles (2D canvas on top of WebGL)
// ---------------------------------------------------------------------------------------------
class Particles {
  constructor(canvas) {
    this.canvas = canvas;
    this.ctx = canvas.getContext('2d');
    this.items = [];
  }
  resize(w, h, dpr) {
    this.canvas.width = w * dpr;
    this.canvas.height = h * dpr;
    this.dpr = dpr;
    this.w = w; this.h = h;
  }
  emit(kind, x, y, n = 20) {
    const presets = {
      confetti: { chars: null, colors: ['#ff6b6b', '#ffd93d', '#6bcB77', '#4d96ff', '#c77dff', '#ff9f1c'] },
      hearts: { chars: ['❤', '💕', '💗'] },
      sparkle: { chars: ['✨', '⭐', '✦'] },
      anger: { chars: ['💢'] },
      sweat: { chars: ['💦'] },
      zzz: { chars: ['💤', 'z', 'Z'] },
      notes: { chars: ['♪', '♫', '🎵'] },
      question: { chars: ['?', '❓'] },
      exclaim: { chars: ['!', '❗'] },
      bubble: { chars: ['💬'] },
      rain: { chars: ['💧'] },
      light: { chars: ['💡'] },
    };
    const p = presets[kind] || presets.sparkle;
    for (let i = 0; i < n; i++) {
      const burst = kind === 'confetti';
      this.items.push({
        kind, x: x + (Math.random() - 0.5) * (burst ? this.w : 60), y: burst ? -20 - Math.random() * this.h * 0.5 : y,
        vx: (Math.random() - 0.5) * (burst ? 60 : 80), vy: burst ? 60 + Math.random() * 120 : -40 - Math.random() * 80,
        rot: Math.random() * 6, vr: (Math.random() - 0.5) * 8,
        life: burst ? 6 : 1.6 + Math.random() * 0.8, age: 0,
        size: burst ? 6 + Math.random() * 6 : 14 + Math.random() * 12,
        color: p.colors ? p.colors[(Math.random() * p.colors.length) | 0] : null,
        ch: p.chars ? p.chars[(Math.random() * p.chars.length) | 0] : null,
        gravity: burst ? 30 : -10,
      });
    }
  }
  get active() { return this.items.length > 0; }
  update(dt) {
    const c = this.ctx;
    c.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    c.clearRect(0, 0, this.w, this.h);
    this.items = this.items.filter((p) => (p.age += dt) < p.life && p.y < this.h + 40);
    for (const p of this.items) {
      p.vy += p.gravity * dt;
      p.x += p.vx * dt + (p.kind === 'confetti' ? Math.sin(p.age * 3 + p.rot) * 20 * dt : 0);
      p.y += p.vy * dt;
      p.rot += p.vr * dt;
      const a = Math.min(1, (p.life - p.age) / 0.5);
      c.save();
      c.globalAlpha = a;
      c.translate(p.x, p.y);
      c.rotate(p.kind === 'confetti' ? p.rot : Math.sin(p.rot) * 0.3);
      if (p.ch) {
        c.font = `${p.size}px sans-serif`;
        c.textAlign = 'center';
        c.fillStyle = p.kind === 'zzz' ? '#7c8cff' : p.kind === 'notes' ? '#ff7eb6' : '#ffb300';
        c.fillText(p.ch, 0, 0);
      } else {
        c.fillStyle = p.color;
        c.fillRect(-p.size / 2, -p.size / 4, p.size, p.size / 2);
      }
      c.restore();
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Speech bubbles (HTML)
// ---------------------------------------------------------------------------------------------
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

class Bubbles {
  constructor(stage, layer) {
    this.stage = stage;
    this.layer = layer;
    this.items = new Map(); // actorId -> {el, until, opts}
    this.focusId = null;
  }

  show(actorId, opts) {
    this.hide(actorId, true);
    const el = document.createElement('div');
    el.className = 'bubble' + (opts.urgent ? ' urgent' : '') + (opts.ai ? ' ai' : '');
    el.style.setProperty('--accent', opts.color || '#ff8a65');
    const name = opts.name ? `<div class="bname"><span class="dot"></span>${escapeHtml(opts.name)}${opts.ai ? '<span class="aibadge">AI</span>' : ''}${opts.badge ? `<span class="badge">${escapeHtml(opts.badge)}</span>` : ''}</div>` : '';
    const text = `<div class="btext">${escapeHtml(opts.text || '').replace(/\n/g, '<br>')}</div>`;
    const reasons = opts.reasons && opts.reasons.length
      ? `<details class="why"><summary>なぜ？（AIの判断根拠）</summary><ul>${opts.reasons.map((r) => `<li>${escapeHtml(r)}</li>`).join('')}</ul></details>`
      : '';
    const replies = opts.replies && opts.replies.length
      ? `<div class="replies">${opts.replies.map((r, i) => `<button data-i="${i}">${escapeHtml(r)}</button>`).join('')}</div>`
      : '';
    const extra = opts.audio ? `<div class="voice">🔊 ボイスメッセージ</div>` : '';
    el.innerHTML = name + text + extra + reasons + replies + '<div class="tail"></div>';
    el.addEventListener('click', (ev) => {
      const b = ev.target.closest('button');
      if (b) {
        ev.stopPropagation();
        post('reply', { id: actorId, ref: opts.ref, text: opts.replies[+b.dataset.i], index: +b.dataset.i });
        el.classList.add('sent');
        setTimeout(() => this.hide(actorId), 600);
        return;
      }
      if (ev.target.closest('details')) return;
      if (el.classList.contains('was-collapsed')) { el.classList.remove('was-collapsed'); return; }
      post('bubbleTap', { id: actorId, ref: opts.ref });
    });
    this.layer.appendChild(el);
    requestAnimationFrame(() => el.classList.add('show'));
    const mini = document.createElement('div');
    mini.className = 'mini';
    mini.innerHTML = `<span class="dot"></span><b>${escapeHtml(opts.name || '')}</b> ${escapeHtml((opts.text || '').replace(/\s+/g, ' ').slice(0, 14))}${(opts.text || '').length > 14 ? '…' : ''}`;
    el.appendChild(mini);
    const item = { el, opts, shownAt: performance.now(), until: opts.sticky ? Infinity : performance.now() + (opts.duration ?? Math.min(14000, 3500 + (opts.text || '').length * 180)) };
    el.addEventListener('pointerdown', () => { if (el.classList.contains('collapsed')) { this.focusId = actorId; item.until = Math.max(item.until, performance.now() + 6000); } }, true);
    this.items.set(actorId, item);
    this.layout();
    post('bubbleSize', this.size());
  }

  hide(actorId, immediate = false) {
    const item = this.items.get(actorId);
    if (!item) return;
    this.items.delete(actorId);
    if (immediate) item.el.remove();
    else {
      item.el.classList.remove('show');
      setTimeout(() => item.el.remove(), 250);
    }
    post('bubbleHidden', { id: actorId, ref: item.opts.ref });
  }

  size() {
    let h = 0;
    for (const { el } of this.items.values()) h = Math.max(h, el.offsetHeight);
    return { height: h };
  }

  // Place each bubble above its actor's head; resolve overlaps by stacking (greedy by x).
  layout() {
    const st = this.stage;
    const now = performance.now();
    const placed = [];
    const entries = [...this.items.entries()].map(([id, item]) => {
      const actor = st.actors.get(id);
      const p = actor ? actor.headScreenPos(st.camera, st.width, st.height) : null;
      return { id, item, p };
    }).filter((e) => e.p).sort((a, b) => a.p.x - b.p.x);

    // Readability: when several bubbles are visible only one is expanded (urgent first, then the
    // one the user tapped, then the newest); the others collapse into one-line chips.
    const alive = entries.filter((e) => now <= e.item.until);
    let focus = null;
    if (alive.length > 1) {
      const urgent = alive.filter((e) => e.item.opts.urgent).sort((a, b) => b.item.shownAt - a.item.shownAt)[0];
      const tapped = alive.find((e) => e.id === this.focusId);
      focus = (tapped || urgent || [...alive].sort((a, b) => b.item.shownAt - a.item.shownAt)[0]).id;
    }
    for (const e of alive) {
      const col = focus !== null && e.id !== focus;
      if (col !== e.item.el.classList.contains('collapsed')) {
        e.item.el.classList.toggle('collapsed', col);
        if (!col) e.item.el.classList.add('was-collapsed');
      }
    }
    // Expanded bubble is placed first so that chips move around it.
    entries.sort((a, b) => (a.id === focus ? -1 : b.id === focus ? 1 : a.p.x - b.p.x));
    for (const e of entries) {
      if (now > e.item.until) { this.hide(e.id); continue; }
      const el = e.item.el;
      const margin = 6;
      // never wider than the part of the stage that is actually visible (overlay window near a screen edge)
      const avail = Math.max(120, (st.clipRight ?? st.width) - (st.clipLeft ?? 0) - 2 * margin);
      const mw = `min(var(--bubble-max, min(78vw, 300px)), ${avail}px)`;
      if (el.style.maxWidth !== mw) el.style.maxWidth = mw;
      const w = el.offsetWidth, h = el.offsetHeight;
      const cl = st.clipLeft ?? 0, cr = st.clipRight ?? st.width;
      let left = Math.max(cl + margin, Math.min(cr - w - margin, e.p.x - w / 2));
      let bottom = e.p.y - 4; // bubble bottom sits above the head
      if (st.bubbleTop != null) bottom = Math.min(bottom, st.bubbleTop);
      let top = bottom - h;
      // Avoid overlaps with previously placed bubbles by moving up.
      let moved = true, guard = 0;
      while (moved && guard++ < 10) {
        moved = false;
        for (const r of placed) {
          if (left < r.right + 4 && left + w + 4 > r.left && top < r.bottom + 4 && top + h + 4 > r.top) {
            top = r.top - h - 6;
            moved = true;
          }
        }
      }
      if (top < margin) {
        // No room above: keep it at the top, centred on the speaker; only if it would cover another
        // bubble, slide sideways to the free spot closest to the speaker.
        top = margin;
        const hits = (l) => placed.some((r) => l < r.right + 4 && l + w + 4 > r.left && top < r.bottom + 4 && top + h + 4 > r.top);
        if (hits(left)) {
          const cands = [...placed.map((r) => r.right + 6), ...placed.map((r) => r.left - w - 6), cl + margin, cr - w - margin]
            .map((c) => Math.max(cl + margin, Math.min(cr - w - margin, c)))
            .sort((x, y) => Math.abs(x + w / 2 - e.p.x) - Math.abs(y + w / 2 - e.p.x));
          const free = cands.find((l) => !hits(l));
          if (free !== undefined) left = free;
        }
      }
      placed.push({ left, right: left + w, top, bottom: top + h });
      el.style.transform = `translate(${left.toFixed(1)}px, ${top.toFixed(1)}px)`;
      const tailX = Math.max(16, Math.min(w - 16, e.p.x - left));
      el.style.setProperty('--tail', `${tailX}px`);
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Stage
// ---------------------------------------------------------------------------------------------
class Stage {
  constructor(root) {
    this.rootEl = root;
    this.actors = new Map();
    this.loading = new Map();
    this.paused = false;
    this.fps = 30;
    this.layoutMode = 'single'; // 'single' | 'row'
    this.modelFraction = 1; // fraction of the canvas height (from the bottom) used by models
    this.fontScale = 1;
    this.cache = new Map(); // url -> Promise<ArrayBuffer>

    this.canvas = document.createElement('canvas');
    this.canvas.className = 'gl';
    root.appendChild(this.canvas);
    this.fx = document.createElement('canvas');
    this.fx.className = 'fx';
    root.appendChild(this.fx);
    this.bubbleLayer = document.createElement('div');
    this.bubbleLayer.className = 'bubbles';
    root.appendChild(this.bubbleLayer);

    this.renderer = new THREE.WebGLRenderer({ canvas: this.canvas, alpha: true, antialias: true, premultipliedAlpha: true, powerPreference: 'low-power' });
    this.renderer.setClearColor(0x000000, 0);
    this.renderer.outputColorSpace = THREE.SRGBColorSpace;
    this.scene = new THREE.Scene();
    this.camera = new THREE.PerspectiveCamera(22, 1, 0.1, 50);
    const light = new THREE.DirectionalLight(0xffffff, Math.PI * 0.95);
    light.position.set(0.6, 1.4, 1.8);
    this.scene.add(light);
    this.keyLight = light;
    this.ambient = new THREE.AmbientLight(0xffffff, Math.PI * 0.35);
    this.scene.add(this.ambient);

    this.room = new Room(this);
    this.director = null;
    this.particles = new Particles(this.fx);
    this.bubbles = new Bubbles(this, this.bubbleLayer);
    this.loader = new GLTFLoader();
    this.loader.register((parser) => new VRMLoaderPlugin(parser));
    this.clock = new THREE.Clock();
    this.acc = 0;
    this.lookPoint = null;

    this.resize();
    window.addEventListener('resize', () => this.resize());
    this.canvas.addEventListener('pointerdown', (e) => this._onPointer(e));
    this._loop = this._loop.bind(this);
    requestAnimationFrame(this._loop);
  }

  resize() {
    const w = this.rootEl.clientWidth || window.innerWidth;
    const h = this.rootEl.clientHeight || window.innerHeight;
    this.width = w; this.height = h;
    const dpr = Math.min(window.devicePixelRatio || 1, 2);
    this.renderer.setPixelRatio(dpr);
    this.renderer.setSize(w, h, false);
    this.particles.resize(w, h, dpr);
    this.room.resize(w, h, dpr);
    this.frame();
  }

  toScreen(x, y) {
    tmpV.set(x, y, 0).project(this.camera);
    return { x: (tmpV.x * 0.5 + 0.5) * this.width, y: (-tmpV.y * 0.5 + 0.5) * this.height };
  }

  // Living-room mode: a fixed room; chibis stand on the floor and move around inside it.
  frameRoom() {
    const aspect = this.width / this.height;
    const fov = this.camera.fov * Math.PI / 180;
    const all = [...this.actors.values()];
    const H = all.length ? Math.max(...all.map((a) => a.height)) : 1.5;
    // Fit the height to the room's content (floor .. top of the window / bubbles above the heads) and let the
    // room be exactly as wide as the screen shows, so there is no empty wall above the chibis.
    // bigger families: slightly smaller chibis so everyone fits in the room
    const frac = Math.min(this.modelFraction < 1 ? this.modelFraction : 0.62, 0.64 - 0.04 * Math.max(0, (this.room.members || 3) - 3));
    let visH = Math.max(H / Math.max(0.44, frac), 2.7);
    let visW = visH * aspect;
    if (visW < 4.2) { visW = 4.2; visH = visW / aspect; } // portrait (lock screen): keep the whole room
    this.room.width = visW;
    const bottom = -visH * 0.07;
    const centerY = bottom + visH / 2;
    const dist = (visH / 2) / Math.tan(fov / 2);
    this.camera.aspect = aspect;
    this.camera.position.set(0, centerY, dist);
    this.camera.lookAt(0, centerY, 0);
    this.camera.updateProjectionMatrix();
    this.worldPerPx = visH / this.height;
  }

  // Frame the camera so that all actors fit in the lower `modelFraction` of the canvas.
  frame() {
    if (!this.width) return;
    if (this.layoutMode === 'room') { this.frameRoom(); return; }
    const actors = [...this.actors.values()].filter((a) => a.root.visible);
    const H = actors.length ? Math.max(...actors.map((a) => a.height)) : 1.5;
    let minX = 0, maxX = 0;
    if (this.layoutMode === 'row' && actors.length) {
      minX = Math.min(...actors.map((a) => a.targetX ?? a.x)) - 0.35;
      maxX = Math.max(...actors.map((a) => a.targetX ?? a.x)) + 0.35;
    } else {
      minX = -0.38; maxX = 0.38;
    }
    const aspect = this.width / this.height;
    const fov = this.camera.fov * Math.PI / 180;
    const modelPx = this.height * this.modelFraction;
    const pad = 0.04;
    // visible world height of the full canvas so that H (+padding) fits in modelPx
    let V = (H * (1 + pad * 2)) * (this.height / modelPx);
    // make sure width fits too
    const needW = (maxX - minX) * 1.05;
    if (V * aspect < needW) V = needW / aspect;
    const bottom = -H * pad * (V / (H * (1 + pad * 2))) - 0.02;
    const centerY = bottom + V / 2;
    const dist = (V / 2) / Math.tan(fov / 2);
    this.camera.aspect = aspect;
    const cx = (minX + maxX) / 2;
    this.camera.position.set(cx, centerY, dist);
    this.camera.lookAt(cx, centerY, 0);
    this.camera.updateProjectionMatrix();
    this.worldPerPx = V / this.height;
    // Where bubbles may extend down to (px from top): just above the tallest head.
    this.bubbleTop = null;
  }

  async _fetchVrm(url) {
    const r = await fetch(url);
    if (!r.ok) throw new Error(`HTTP ${r.status}`);
    return r.arrayBuffer();
  }

  // VRM avatars ship 2k-4k textures; a chibi on a phone needs far less. Downscaling right after
  // parsing (before the first GPU upload) cuts memory by ~90% and keeps the resident app light.
  _shrinkTextures(root, maxSize) {
    const seen = new Set();
    const shrink = (tex) => {
      if (!tex || !tex.isTexture || seen.has(tex)) return;
      seen.add(tex);
      const img = tex.image;
      const w = img && (img.width || img.naturalWidth), h = img && (img.height || img.naturalHeight);
      if (!w || !h || Math.max(w, h) <= maxSize) return;
      const k = maxSize / Math.max(w, h);
      const c = document.createElement('canvas');
      c.width = Math.max(1, Math.round(w * k)); c.height = Math.max(1, Math.round(h * k));
      c.getContext('2d').drawImage(img, 0, 0, c.width, c.height);
      if (img.close) img.close();
      tex.image = c;
      tex.needsUpdate = true;
    };
    root.traverse((o) => {
      if (!o.material) return;
      for (const m of Array.isArray(o.material) ? o.material : [o.material]) {
        for (const v of Object.values(m)) shrink(v);
        if (m.uniforms) for (const u of Object.values(m.uniforms)) shrink(u && u.value);
      }
    });
  }

  async addActor(id, url, opts = {}) {
    if (this.actors.has(id)) {
      const a = this.actors.get(id);
      if (a.opts.url === url) { Object.assign(a.opts, opts); a.name = opts.name ?? a.name; a.color = opts.color ?? a.color; return a; }
      this.removeActor(id);
    }
    if (this.loading.has(id)) return this.loading.get(id);
    const p = (async () => {
      post('loading', { id });
      const buf = await this._fetchVrm(url);
      const gltf = await this.loader.parseAsync(buf, '');
      const vrm = gltf.userData.vrm;
      if (!vrm) throw new Error('not a VRM');
      VRMUtils.removeUnnecessaryVertices(gltf.scene);
      if (VRMUtils.combineSkeletons) VRMUtils.combineSkeletons(gltf.scene);
      VRMUtils.rotateVRM0(vrm); // VRM0 faces -Z; normalize to +Z like VRM1
      if (this.maxTexture) this._shrinkTextures(vrm.scene, this.maxTexture);
      vrm.scene.traverse((o) => { o.frustumCulled = false; });
      const actor = new Actor(this, id, vrm, { ...opts, url });
      actor.root.position.x = opts.x ?? 0;
      this.actors.set(id, actor);
      if (this.layoutMode === 'room' && this.director) {
        this.room.setMembersCount(this.actors.size);
        this.frame();
        actor.root.position.x = this.director.freeSpot(actor);
        const pr = this.director.presence.get(id);
        if (pr) this.director.setPresence(id, pr);
      } else if (this.layoutMode === 'row') this.arrangeRow();
      this.frame();
      const meta = vrm.meta || {};
      post('loaded', {
        id,
        meta: {
          name: meta.name || meta.title || '',
          authors: meta.authors || (meta.author ? [meta.author] : []),
          license: meta.licenseUrl || meta.licenseName || '',
          creditNotation: meta.creditNotation || '',
          modification: meta.modification || '',
        },
      });
      return actor;
    })();
    this.loading.set(id, p);
    try {
      return await p;
    } catch (e) {
      post('error', { id, message: String(e && e.message || e) });
      throw e;
    } finally {
      this.loading.delete(id);
    }
  }

  removeActor(id) {
    const a = this.actors.get(id);
    if (!a) return;
    this.bubbles.hide(id, true);
    a.dispose();
    this.actors.delete(id);
    this.frame();
  }

  arrangeRow(ids) {
    if (ids) this.rowIds = ids;
    if (this.layoutMode === 'room' && this.director) { this.room.setMembersCount(this.actors.size); this.frame(); this.director.layout(); return; }
    // remembered order; actors that are still loading take their place when they arrive
    const list = (ids || this.rowIds || [...this.actors.keys()]).filter((id) => this.actors.has(id) && this.actors.get(id).root.visible);
    const gap = 0.62;
    list.forEach((id, i) => {
      const a = this.actors.get(id);
      if (a) a.root.position.x = (i - (list.length - 1) / 2) * gap;
    });
    this.frame();
  }

  _onPointer(e) {
    // Tap on an actor -> event (poke). Uses a raycast against skinned meshes' bounding boxes.
    const rect = this.canvas.getBoundingClientRect();
    const x = ((e.clientX - rect.left) / rect.width) * 2 - 1;
    const y = -((e.clientY - rect.top) / rect.height) * 2 + 1;
    let best = null, bestD = Infinity;
    for (const a of this.actors.values()) {
      const top = a.headScreenPos(this.camera, this.width, this.height);
      tmpV.set(a.x, 0, 0).project(this.camera);
      const footY = (-tmpV.y * 0.5 + 0.5) * this.height;
      const cx = (tmpV.x * 0.5 + 0.5) * this.width;
      const px = e.clientX - rect.left, py = e.clientY - rect.top;
      const halfW = (0.3 / this.worldPerPx) || 60;
      if (top && py > top.y - 20 && py < footY && Math.abs(px - cx) < halfW) {
        const d = Math.abs(px - cx);
        if (d < bestD) { bestD = d; best = a; }
      }
    }
    if (best) {
      const top = best.headScreenPos(this.camera, this.width, this.height);
      const headHit = top && (e.clientY - rect.top) < top.y + (0.35 / this.worldPerPx);
      post('tapActor', { id: best.id, part: headHit ? 'head' : 'body', x, y });
    } else {
      post('tapEmpty', { x, y });
    }
  }

  _loop() {
    requestAnimationFrame(this._loop);
    const dt = Math.min(this.clock.getDelta(), 0.1);
    if (this.paused) return;
    this.acc += dt;
    const step = 1 / this.fps;
    if (this.acc < step * 0.95) return;
    const d = Math.min(this.acc, 0.1);
    this.acc = 0;
    if (this.room.enabled) {
      this.room.draw(d);
      if (this.director) this.director.update(d);
      const dark = !this.room.lightsOn && this.room.isNight;
      this.keyLight.intensity = Math.PI * (dark ? 0.35 : 0.95);
      this.ambient.intensity = Math.PI * (dark ? 0.18 : 0.35);
    }
    for (const a of this.actors.values()) if (a.root.visible) a.update(d, this.camera);
    this.renderer.render(this.scene, this.camera);
    this.particles.update(d);
    if (this.bubbles.items.size) this.bubbles.layout();
    if (this.badges && this.badges.size) {
      for (const [id, el] of this.badges) {
        const a = this.actors.get(id);
        const p = a && a.root.visible ? a.headScreenPos(this.camera, this.width, this.height) : null;
        el.style.display = p && !this.bubbles.items.has(id) ? '' : 'none';
        if (p) el.style.transform = `translate(${(p.x + 14).toFixed(0)}px, ${(p.y + 6).toFixed(0)}px)`;
      }
    }
  }
}

// ---------------------------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------------------------
let stage = null;

// Map emotion -> default motion & particles.
const EMOTION_FX = {
  happy: { motion: 'wave', fx: 'sparkle' },
  love: { motion: 'bothWave', fx: 'hearts', emotion: 'happy' },
  excited: { motion: 'jump', fx: 'sparkle', emotion: 'happy' },
  sad: { motion: 'sad', fx: 'rain' },
  angry: { motion: 'angry', fx: 'anger' },
  surprised: { motion: 'surprised', fx: 'exclaim' },
  relaxed: { motion: 'nod', fx: null },
  worried: { motion: 'think', fx: 'sweat', emotion: 'sad' },
  sleepy: { motion: 'sleepy', fx: 'zzz', emotion: 'relaxed' },
  thanks: { motion: 'bow', fx: 'hearts', emotion: 'happy' },
  neutral: { motion: 'talk', fx: null },
};

const api = {
  version: '1.0.0',
  motions: Object.keys(MOTIONS),
  init(opts = {}) {
    if (stage) return api;
    const root = opts.root || document.getElementById('stage') || document.body;
    stage = new Stage(root);
    if (opts.fps) stage.fps = opts.fps;
    stage.maxTexture = opts.maxTexture ?? 1024;
    api.configure(opts);
    post('ready', { motions: Object.keys(MOTIONS) });
    return api;
  },
  configure(opts = {}) {
    if (!stage) return;
    if (opts.layout) stage.layoutMode = opts.layout;
    if (opts.modelFraction) stage.modelFraction = opts.modelFraction;
    if (opts.fps) stage.fps = opts.fps;
    if (opts.fontScale) {
      stage.fontScale = opts.fontScale;
      document.documentElement.style.setProperty('--font-scale', String(opts.fontScale));
    }
    if (opts.theme) document.documentElement.dataset.theme = opts.theme;
    if ('clipLeft' in opts) stage.clipLeft = opts.clipLeft;
    if ('clipRight' in opts) stage.clipRight = opts.clipRight;
    if (opts.maxBubbleWidth) document.documentElement.style.setProperty('--bubble-max', opts.maxBubbleWidth + 'px');
    stage.frame();
  },
  async add(id, url, opts = {}) {
    try {
      const a = await stage.addActor(id, url, opts);
      if (opts.motion) a.play(opts.motion);
      return true;
    } catch (e) { return false; }
  },
  remove(id) { stage.removeActor(id); },
  // ---- living room (presence) ------------------------------------------------------------
  room(opts = {}) {
    stage.layoutMode = 'room';
    stage.room.enabled = true;
    if (!stage.director) stage.director = new Director(stage, stage.room, api);
    if ('lights' in opts) stage.room.lightsOn = !!opts.lights;
    if ('hour' in opts) stage.room.hourOverride = opts.hour;
    if (opts.members) stage.room.declared = opts.members;
    stage.room.setMembersCount(stage.actors.size);
    stage.frame();
    stage.director.layout();
  },
  presence(id, p, animate = false) { if (stage.director) stage.director.setPresence(id, p, animate); },
  arrive(id, text) { if (stage.director) stage.director.arrive(id, text); },
  leave(id, p) { if (stage.director) stage.director.leave(id, p); },
  lights(on) { stage.room.lightsOn = !!on; },
  visible(id, on) {
    const a = stage.actors.get(id);
    if (!a) return;
    a.root.visible = !!on;
    if (!on) stage.bubbles.hide(id, true);
    stage.frame();
  },
  // Show only this actor (others stay loaded but hidden) - used by the single-chibi overlay.
  only(id) {
    for (const [k, a] of stage.actors) { a.root.visible = k === id; if (k !== id) stage.bubbles.hide(k, true); }
    stage.frame();
  },
  badge(id, text) {
    const a = stage.actors.get(id);
    if (!a) return;
    stage.badges = stage.badges || new Map();
    let el = stage.badges.get(id);
    if (!text) { if (el) { el.remove(); stage.badges.delete(id); } return; }
    if (!el) { el = document.createElement('div'); el.className = 'cbadge'; stage.bubbleLayer.appendChild(el); stage.badges.set(id, el); }
    el.textContent = text;
  },
  clear() { for (const id of [...stage.actors.keys()]) stage.removeActor(id); },
  ids() { return [...stage.actors.keys()]; },
  has(id) { return stage.actors.has(id); },
  play(id, motion, opts = {}) { const a = stage.actors.get(id); return a ? a.play(motion, opts) : false; },
  sequence(id, list) { const a = stage.actors.get(id); if (a) a.sequence(list); },
  emotion(id, emotion, weight = 1) { const a = stage.actors.get(id); if (a) a.setEmotion(emotion, weight); },
  facing(id, dir) {
    // dir: -1 = walking left, 1 = walking right, 0 = front
    const a = stage.actors.get(id);
    if (a) a.setFacing(dir * Math.PI * 0.42);
  },
  walk(id, dir) {
    const a = stage.actors.get(id);
    if (!a) return;
    if (dir === 0) { a.setFacing(0); a._next(); } else { a.setFacing(dir * Math.PI * 0.42); a.play('walk'); }
  },
  walkTo(id, x, speed) { const a = stage.actors.get(id); if (a) { a.walkTo(x, speed); stage.frame(); } },
  arrangeRow(ids) { if (stage.layoutMode !== 'room') stage.layoutMode = 'row'; stage.arrangeRow(ids); },
  speaking(id, on) {
    const a = stage.actors.get(id);
    if (!a) return;
    a.speaking = !!on;
    if (on && (a.motion.name === 'idle')) a.play('talk');
    if (!on && a.motion.name === 'talk') a.play('idle');
  },
  mouth(id, level) {
    const a = stage.actors.get(id);
    if (!a) return;
    a.mouthLevel = Math.max(0, Math.min(1, level));
    a.mouthExternalUntil = performance.now() + 250;
  },
  carried(id, on, velX = 0) {
    const a = stage.actors.get(id);
    if (!a) return;
    a.carried = on;
    a.velX = velX;
    if (on && a.motion.name !== 'carried') a.play('carried', { fade: 0.15 });
    if (!on && a.motion.name === 'carried') a._next();
  },
  inertia(id, ax, ay) { const a = stage.actors.get(id); if (a) a.setInertia(ax, ay); },
  say(id, opts) {
    const a = stage.actors.get(id);
    const o = typeof opts === 'string' ? { text: opts } : { ...opts };
    if (a) { o.name = o.name ?? a.name; o.color = o.color ?? a.color; o.ai = o.ai ?? a.isAI; }
    stage.bubbles.show(id, o);
  },
  unsay(id) { stage.bubbles.hide(id); },
  bubbleHeight() { return stage.bubbles.size().height; },
  fx(kind, id, n) {
    const a = id ? stage.actors.get(id) : null;
    const p = a ? a.headScreenPos(stage.camera, stage.width, stage.height) : { x: stage.width / 2, y: stage.height / 3 };
    stage.particles.emit(kind, p ? p.x : stage.width / 2, p ? p.y : stage.height / 3, n ?? (kind === 'confetti' ? 120 : 8));
  },
  // High-level: deliver a message with emotion-appropriate motion, face and particles.
  deliver(id, msg = {}) {
    const a = stage.actors.get(id);
    if (!a) return false;
    const key = msg.emotion || 'neutral';
    const conf = EMOTION_FX[key] || EMOTION_FX.neutral;
    if (stage.director && stage.layoutMode === 'room') {
      const d = stage.director;
      if (a.motion.name === 'sleepSit') d.standUp(a);
      a.targetX = null; a.onArrive = null; a.setFacing(0);
      a.roomBusyUntil = d.clock + 5;
      if (!a.root.visible) {
        // Someone who is out "peeks in" through the front door to deliver it, then goes back out.
        a.root.position.x = stage.room.doorX;
        a.root.visible = true;
        stage.room.doorTarget = 1;
        a.visiting = true;
        const back = () => {
          if (!a.visiting) return;
          a.visiting = false;
          const pr = d.presence.get(id);
          if (pr && !pr.home) { a.root.visible = false; stage.room.doorTarget = 0; }
        };
        const until = msg.sticky ? 60000 : (msg.duration ?? Math.min(14000, 3500 + (msg.text || '').length * 180)) + 800;
        setTimeout(back, until);
      }
    }
    a.setEmotion(conf.emotion || (EMOTION_FX[key] ? key : 'neutral'), msg.intensity ?? 0.85);
    const motion = msg.motion || conf.motion;
    const seq = [{ motion }];
    if (MOTIONS[motion] && MOTIONS[motion].loop && motion !== 'talk') seq[0].duration = msg.motionDuration ?? 3;
    a.sequence(seq);
    if (msg.fx !== false && (msg.fx || conf.fx)) api.fx(msg.fx || conf.fx, id);
    if (msg.text != null) api.say(id, msg);
    post('delivered', { id, ref: msg.ref });
    return true;
  },
  party(on = true) {
    if (!on) return;
    if (stage.layoutMode === 'room' && stage.director) {
      // Everyone gathers in the living room - even the family members who are out (they "come home" for the party).
      const d = stage.director;
      const saved = new Map(d.presence);
      for (const a of stage.actors.values()) { a.root.visible = true; d.seatOf.delete(a.id); stage.room.away.delete(a.id); a.roomBusyUntil = d.clock + 14; }
      const list = [...stage.actors.values()];
      const [x0, x1] = [stage.room.left + 0.5, stage.room.right - 0.5];
      list.forEach((a, i) => { a.root.position.x = list.length === 1 ? 0 : x0 + (x1 - x0) * (i / (list.length - 1)); a.targetX = null; });
      stage.room.lightsOn = true;
      setTimeout(() => { for (const [id, p] of saved) d.setPresence(id, p); }, 16000);
    } else {
      stage.layoutMode = 'row';
      stage.arrangeRow();
    }
    let i = 0;
    for (const a of stage.actors.values()) {
      a.setEmotion('happy', 1);
      setTimeout(() => a.sequence([{ motion: 'cheer' }, { motion: 'dance', duration: 6 }, { motion: 'bothWave' }]), i++ * 180);
    }
    api.fx('confetti');
    setTimeout(() => api.fx('confetti'), 2500);
  },
  lookAt(x, y) {
    // Normalized screen coords (-1..1). null resets to camera.
    if (x == null) { stage.lookPoint = null; return; }
    const v = new THREE.Vector3(x, y, 0.5).unproject(stage.camera);
    const dir = v.sub(stage.camera.position).normalize();
    stage.lookPoint = stage.camera.position.clone().add(dir.multiplyScalar(stage.camera.position.z * 0.9));
  },
  pause(p = true) { stage.paused = p; if (!p) stage.clock.getDelta(); },
  resize() { stage.resize(); },
  snapshot() { stage.renderer.render(stage.scene, stage.camera); return stage.canvas.toDataURL('image/png'); },
  _stage: () => stage,
};

window.Chibi = api;
export default api;
