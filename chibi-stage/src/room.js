// The family living room: a 2D room drawn behind the 3D chibis, plus a small "director" that makes
// the room feel alive and reflects each member's real presence (at home / out / asleep / moving).
//
//   window  : sky follows the real clock (morning, day, sunset, night with moon & stars)
//   sofa    : sleeping members doze here, others sometimes sit down
//   door    : members who come home walk in through it ("ただいま！"), leaving members walk out
//   board   : "おでかけボード" lists who is out and where (from their calendar / state)
//   lights  : the real living-room light (IoT via MCP) - turn it off at night and the room goes dark
//
// Everything is in world units on the z=0 plane; the floor is y=0.

const TAU = Math.PI * 2;

function lerpColor(a, b, t) {
  const pa = a.match(/\w\w/g).map((h) => parseInt(h, 16));
  const pb = b.match(/\w\w/g).map((h) => parseInt(h, 16));
  return `rgb(${pa.map((v, i) => Math.round(v + (pb[i] - v) * t)).join(',')})`;
}

// [hour, top, bottom] sky keyframes
const SKY = [
  [0, '0b1030', '1c2350'], [4.5, '101a44', '2a2f5e'], [6, 'f7a78b', 'ffe2b8'], [8, '7ec8f5', 'd6f0ff'],
  [15.5, '6fbdf2', 'cfeeff'], [17.3, 'f58a5a', 'ffd29a'], [18.6, '3b3a78', 'c07a8a'], [19.6, '141a44', '2b2d62'], [24, '0b1030', '1c2350'],
];

function skyAt(h) {
  for (let i = 0; i < SKY.length - 1; i++) {
    const [h0, t0, b0] = SKY[i];
    const [h1, t1, b1] = SKY[i + 1];
    if (h >= h0 && h <= h1) {
      const k = (h - h0) / (h1 - h0);
      return [lerpColor(t0, t1, k), lerpColor(b0, b1, k)];
    }
  }
  return ['#7ec8f5', '#d6f0ff'];
}

export class Room {
  constructor(stage) {
    this.stage = stage;
    this.canvas = document.createElement('canvas');
    this.canvas.className = 'chibi-room';
    stage.rootEl.insertBefore(this.canvas, stage.rootEl.firstChild);
    this.ctx = this.canvas.getContext('2d');
    this.enabled = false;
    this.width = 4.2; // world units
    this.doorOpen = 0;
    this.doorTarget = 0;
    this.lightsOn = true;
    this.away = new Map(); // id -> {name, color, label, icon}
    this.stars = Array.from({ length: 40 }, () => [Math.random(), Math.random() * 0.8, Math.random()]);
    this.time = 0;
    this.hourOverride = null;
    this.seatY = 0.42;
  }

  get hour() {
    if (this.hourOverride != null) return this.hourOverride;
    const d = new Date();
    return d.getHours() + d.getMinutes() / 60;
  }

  get isNight() { const h = this.hour; return h >= 19 || h < 5.5; }

  // ---------------------------------------------------------------- layout helpers (world units)
  get left() { return -this.width / 2; }
  get right() { return this.width / 2; }
  get doorX() { return this.right - 0.5; }
  get sofa() { return { x0: this.left + 0.18, x1: this.left + 1.62 }; }
  get seats() { const s = this.sofa; return [s.x0 + 0.42, s.x1 - 0.42]; }
  get floorRange() { return [this.sofa.x1 + 0.12, this.doorX - 0.42]; }
  get winX0() { return this.sofa.x1 + 0.2; }
  get winX1() { return Math.min(this.doorX - 0.62, this.winX0 + 1.3); }

  resize(w, h, dpr) {
    this.canvas.width = Math.round(w * dpr);
    this.canvas.height = Math.round(h * dpr);
    this.dpr = dpr;
  }

  setMembersCount(n) {
    this.members = Math.max(n, this.declared || 0); // width itself follows the viewport (Stage.frameRoom)
  }

  // ---------------------------------------------------------------- drawing
  draw(dt) {
    this.time += dt;
    this.doorOpen += (this.doorTarget - this.doorOpen) * Math.min(1, dt * 6);
    const c = this.ctx;
    const st = this.stage;
    const W = st.width, H = st.height;
    c.setTransform(this.dpr, 0, 0, this.dpr, 0, 0);
    c.clearRect(0, 0, W, H);
    if (!this.enabled) return;
    const P = (x, y) => st.toScreen(x, y);
    const dark = this.isNight && !this.lightsOn;
    const dim = !this.lightsOn && !this.isNight ? 0.12 : 0;

    const floor = P(0, 0).y;
    const unit = Math.abs(P(0, 1).y - floor); // px per world unit
    // wall
    const wallTop = this.isNight ? '#e9dccb' : '#fbf1e4';
    const g = c.createLinearGradient(0, 0, 0, floor);
    g.addColorStop(0, wallTop); g.addColorStop(1, '#f3e2cf');
    c.fillStyle = g; c.fillRect(0, 0, W, floor);
    // wainscot
    c.fillStyle = '#ead3b8'; c.fillRect(0, floor - unit * 0.28, W, unit * 0.28);
    c.fillStyle = '#d9bd9c'; c.fillRect(0, floor - unit * 0.3, W, unit * 0.025);
    // floor (wood)
    const fg = c.createLinearGradient(0, floor, 0, H);
    fg.addColorStop(0, '#c99a6b'); fg.addColorStop(1, '#a87a4f');
    c.fillStyle = fg; c.fillRect(0, floor, W, H - floor);
    c.strokeStyle = 'rgba(90,55,30,.18)'; c.lineWidth = 1;
    for (let x = -12; x < 12; x += 0.55) {
      const a = P(x, 0), b = P(x * 1.25, -0.6);
      c.beginPath(); c.moveTo(a.x, floor); c.lineTo(b.x, H); c.stroke();
    }
    // rug
    const r0 = P(-0.9, 0), r1 = P(1.4, 0);
    c.fillStyle = 'rgba(232,116,90,.28)';
    c.beginPath(); c.ellipse((r0.x + r1.x) / 2, floor + unit * 0.09, (r1.x - r0.x) / 2, unit * 0.07, 0, 0, TAU); c.fill();

    this._window(c, P, unit);
    this._clock(c, P, unit);
    this._board(c, P, unit);
    this._door(c, P, unit, floor);
    this._lamp(c, P, unit);
    this._sofa(c, P, unit, floor);
    this._plant(c, P, unit, floor);

    if (dark || dim) {
      c.fillStyle = dark ? 'rgba(12,16,42,.58)' : `rgba(20,20,40,${dim})`;
      c.fillRect(0, 0, W, H);
      if (dark) { // moonlight through the window
        const w0 = P(this.winX0, 2.05), w1 = P(this.winX1, 1.0);
        const mg = c.createLinearGradient(w0.x, w1.y, w0.x, H);
        mg.addColorStop(0, 'rgba(160,190,255,.20)'); mg.addColorStop(1, 'rgba(160,190,255,0)');
        c.fillStyle = mg;
        c.beginPath(); c.moveTo(w0.x, w1.y); c.lineTo(w1.x, w1.y); c.lineTo(w1.x + unit * 0.6, H); c.lineTo(w0.x + unit * 0.3, H); c.fill();
      }
    }
  }

  _roundRect(c, x, y, w, h, r) {
    c.beginPath(); c.moveTo(x + r, y); c.arcTo(x + w, y, x + w, y + h, r); c.arcTo(x + w, y + h, x, y + h, r);
    c.arcTo(x, y + h, x, y, r); c.arcTo(x, y, x + w, y, r); c.closePath();
  }

  _window(c, P, unit) {
    const a = P(this.winX0, 2.05), b = P(this.winX1, 1.0);
    const [top, bot] = skyAt(this.hour);
    const g = c.createLinearGradient(0, a.y, 0, b.y);
    g.addColorStop(0, top); g.addColorStop(1, bot);
    c.fillStyle = g; c.fillRect(a.x, a.y, b.x - a.x, b.y - a.y);
    const h = this.hour;
    if (this.isNight) {
      c.fillStyle = 'rgba(255,255,255,.85)';
      for (const [sx, sy, tw] of this.stars) {
        const s = 1 + (Math.sin(this.time * 2 + tw * 10) + 1) * 0.6;
        c.fillRect(a.x + sx * (b.x - a.x), a.y + sy * (b.y - a.y), s, s);
      }
      c.fillStyle = '#fff6c9';
      c.beginPath(); c.arc(a.x + (b.x - a.x) * 0.75, a.y + (b.y - a.y) * 0.28, unit * 0.11, 0, TAU); c.fill();
      c.fillStyle = top; c.beginPath(); c.arc(a.x + (b.x - a.x) * 0.75 + unit * 0.05, a.y + (b.y - a.y) * 0.25, unit * 0.1, 0, TAU); c.fill();
    } else {
      // sun moves across the window during the day
      const k = Math.max(0, Math.min(1, (h - 6) / 12));
      c.fillStyle = h > 16.5 || h < 7 ? '#ffd27a' : '#fff4b0';
      c.beginPath(); c.arc(a.x + (b.x - a.x) * (0.15 + 0.7 * k), a.y + (b.y - a.y) * (0.75 - Math.sin(k * Math.PI) * 0.5), unit * 0.1, 0, TAU); c.fill();
      c.fillStyle = 'rgba(255,255,255,.8)';
      const cx = a.x + ((this.time * 6) % (b.x - a.x + 80)) - 40;
      for (const [dx, dy, r] of [[0, 0, 12], [14, -4, 10], [26, 2, 9]]) { c.beginPath(); c.arc(cx + dx, a.y + (b.y - a.y) * 0.3 + dy, r * unit / 90, 0, TAU); c.fill(); }
    }
    // frame + cross bars
    c.strokeStyle = '#fffaf2'; c.lineWidth = Math.max(3, unit * 0.05);
    c.strokeRect(a.x, a.y, b.x - a.x, b.y - a.y);
    c.beginPath(); c.moveTo((a.x + b.x) / 2, a.y); c.lineTo((a.x + b.x) / 2, b.y); c.moveTo(a.x, (a.y + b.y) / 2); c.lineTo(b.x, (a.y + b.y) / 2); c.stroke();
    // curtains
    c.fillStyle = '#f2a48f';
    for (const [x, dir] of [[a.x, 1], [b.x, -1]]) {
      c.beginPath(); c.moveTo(x - dir * unit * 0.05, a.y - unit * 0.08);
      c.quadraticCurveTo(x + dir * unit * 0.22, (a.y + b.y) / 2, x + dir * unit * 0.05, b.y + unit * 0.12);
      c.lineTo(x - dir * unit * 0.12, b.y + unit * 0.12); c.lineTo(x - dir * unit * 0.12, a.y - unit * 0.08); c.fill();
    }
    c.fillStyle = '#c98a6a'; c.fillRect(a.x - unit * 0.18, a.y - unit * 0.1, b.x - a.x + unit * 0.36, unit * 0.03);
  }

  _clock(c, P, unit) {
    const p = P(this.doorX, 2.12);
    const r = unit * 0.12;
    c.fillStyle = '#fffdf8'; c.strokeStyle = '#8a6a55'; c.lineWidth = Math.max(1.5, unit * 0.02);
    c.beginPath(); c.arc(p.x, p.y, r, 0, TAU); c.fill(); c.stroke();
    const d = new Date();
    const hh = (this.hourOverride ?? (d.getHours() + d.getMinutes() / 60)) % 12;
    const mm = d.getMinutes();
    c.lineCap = 'round';
    c.beginPath(); c.moveTo(p.x, p.y); c.lineTo(p.x + Math.sin(hh / 12 * TAU) * r * 0.5, p.y - Math.cos(hh / 12 * TAU) * r * 0.5); c.stroke();
    c.beginPath(); c.moveTo(p.x, p.y); c.lineTo(p.x + Math.sin(mm / 60 * TAU) * r * 0.78, p.y - Math.cos(mm / 60 * TAU) * r * 0.78); c.stroke();
  }

  _board(c, P, unit) {
    const s = this.sofa;
    const a = P(s.x0 + 0.05, 2.02), b = P(s.x1 - 0.05, 1.08);
    c.fillStyle = '#fbfbf7'; c.strokeStyle = '#b9a28c'; c.lineWidth = Math.max(2, unit * 0.03);
    this._roundRect(c, a.x, a.y, b.x - a.x, b.y - a.y, unit * 0.04); c.fill(); c.stroke();
    const fs = Math.max(9, Math.min(15, unit * 0.1));
    c.fillStyle = '#6b5a4a'; c.font = `700 ${fs}px sans-serif`; c.textBaseline = 'top';
    c.fillText('おでかけボード', a.x + fs * 0.6, a.y + fs * 0.45);
    let y = a.y + fs * 1.9;
    c.font = `${fs}px sans-serif`;
    if (!this.away.size) { c.fillStyle = '#9c8c7c'; c.fillText('みんな家にいるよ', a.x + fs * 0.6, y); }
    for (const v of this.away.values()) {
      if (y > b.y - fs * 1.2) break;
      c.fillStyle = v.color || '#e8745a';
      c.beginPath(); c.arc(a.x + fs * 0.95, y + fs * 0.55, fs * 0.33, 0, TAU); c.fill();
      c.fillStyle = '#4a3f36';
      const maxW = b.x - a.x - fs * 2;
      c.font = `700 ${fs}px sans-serif`;
      c.fillText(`${v.icon || '📍'}${v.name}`, a.x + fs * 1.6, y, maxW);
      if (v.label) {
        c.font = `${fs * 0.85}px sans-serif`;
        c.fillStyle = '#7a6a5a';
        c.fillText(v.label, a.x + fs * 1.6, y + fs * 1.15, maxW);
        y += fs * 1.1;
      }
      y += fs * 1.5;
    }
  }

  _door(c, P, unit, floor) {
    const a = P(this.doorX - 0.36, 1.78), b = P(this.doorX + 0.36, 0);
    c.fillStyle = '#9b7153'; c.fillRect(a.x - unit * 0.05, a.y - unit * 0.05, b.x - a.x + unit * 0.1, floor - a.y + unit * 0.05);
    // doorway (warm light from the entrance when open)
    const dg = c.createLinearGradient(0, a.y, 0, floor);
    dg.addColorStop(0, '#fff1c9'); dg.addColorStop(1, '#f5d79c');
    c.fillStyle = dg; c.fillRect(a.x, a.y, b.x - a.x, floor - a.y);
    // door panel swinging open (shrinks toward the hinge)
    const w = (b.x - a.x) * (1 - 0.82 * this.doorOpen);
    c.fillStyle = '#c28d62'; c.fillRect(a.x, a.y, w, floor - a.y);
    c.strokeStyle = 'rgba(80,45,20,.35)'; c.lineWidth = 1.5;
    c.strokeRect(a.x + w * 0.14, a.y + (floor - a.y) * 0.08, w * 0.72, (floor - a.y) * 0.36);
    c.strokeRect(a.x + w * 0.14, a.y + (floor - a.y) * 0.52, w * 0.72, (floor - a.y) * 0.38);
    c.fillStyle = '#f0c040'; c.beginPath(); c.arc(a.x + w * 0.86, a.y + (floor - a.y) * 0.5, Math.max(2, unit * 0.025), 0, TAU); c.fill();
    // mat
    c.fillStyle = '#6f8f6a'; c.fillRect(a.x - unit * 0.04, floor + unit * 0.01, b.x - a.x + unit * 0.08, unit * 0.05);
  }

  _lamp(c, P, unit) {
    const p = P((this.winX0 + this.winX1) / 2, 2.3);
    c.strokeStyle = '#8a7a6a'; c.lineWidth = 1.5;
    c.beginPath(); c.moveTo(p.x, 0); c.lineTo(p.x, p.y); c.stroke();
    c.fillStyle = '#f6c56e';
    c.beginPath(); c.moveTo(p.x - unit * 0.18, p.y + unit * 0.12); c.lineTo(p.x + unit * 0.18, p.y + unit * 0.12); c.lineTo(p.x + unit * 0.08, p.y); c.lineTo(p.x - unit * 0.08, p.y); c.fill();
    if (this.lightsOn) {
      const lg = c.createRadialGradient(p.x, p.y + unit * 0.14, 1, p.x, p.y + unit * 0.14, unit * 1.3);
      lg.addColorStop(0, this.isNight ? 'rgba(255,220,150,.45)' : 'rgba(255,230,170,.18)'); lg.addColorStop(1, 'rgba(255,220,150,0)');
      c.fillStyle = lg; c.beginPath(); c.arc(p.x, p.y + unit * 0.14, unit * 1.3, 0, TAU); c.fill();
    }
  }

  _sofa(c, P, unit, floor) {
    const s = this.sofa;
    const a = P(s.x0, this.seatY + 0.5), b = P(s.x1, 0);
    const seat = P(0, this.seatY).y;
    c.fillStyle = '#7fa7c9'; // back
    this._roundRect(c, a.x, a.y, b.x - a.x, seat - a.y + unit * 0.05, unit * 0.1); c.fill();
    c.fillStyle = '#6b93b6'; // seat
    this._roundRect(c, a.x, seat - unit * 0.02, b.x - a.x, floor - seat - unit * 0.06, unit * 0.06); c.fill();
    c.fillStyle = '#5d84a6'; // arms
    for (const x of [a.x - unit * 0.06, b.x - unit * 0.12]) { this._roundRect(c, x, seat - unit * 0.22, unit * 0.18, floor - seat + unit * 0.16, unit * 0.07); c.fill(); }
    c.fillStyle = '#ffd98a'; // cushion
    this._roundRect(c, a.x + unit * 0.1, seat - unit * 0.28, unit * 0.26, unit * 0.24, unit * 0.06); c.fill();
    c.fillStyle = '#4b3a2e'; // legs
    for (const x of [a.x + unit * 0.05, b.x - unit * 0.1]) c.fillRect(x, floor - unit * 0.06, unit * 0.05, unit * 0.06);
  }

  _plant(c, P, unit, floor) {
    const p = P(this.floorRange[1] + 0.12, 0);
    c.fillStyle = '#c77b52'; c.beginPath(); c.moveTo(p.x - unit * 0.1, floor - unit * 0.22); c.lineTo(p.x + unit * 0.1, floor - unit * 0.22);
    c.lineTo(p.x + unit * 0.07, floor); c.lineTo(p.x - unit * 0.07, floor); c.fill();
    c.fillStyle = '#5a9e6a';
    for (let i = 0; i < 6; i++) {
      const ang = -Math.PI / 2 + (i - 2.5) * 0.35 + Math.sin(this.time + i) * 0.03;
      c.beginPath(); c.ellipse(p.x + Math.cos(ang) * unit * 0.14, floor - unit * 0.25 + Math.sin(ang) * unit * 0.14, unit * 0.05, unit * 0.13, ang + Math.PI / 2, 0, TAU); c.fill();
    }
  }
}

// ------------------------------------------------------------------------------------------------
// Director: presence-driven, self-running living room
// ------------------------------------------------------------------------------------------------
export class Director {
  constructor(stage, room, api) {
    this.stage = stage;
    this.room = room;
    this.api = api;
    this.presence = new Map(); // id -> {home, state, label}
    this.seatOf = new Map(); // id -> seat index
    this.clock = 0;
  }

  actors() { return [...this.stage.actors.values()]; }
  homeActors() { return this.actors().filter((a) => a.root.visible); }

  busy(a) {
    return a.roomBusyUntil > this.clock || this.stage.bubbles.items.has(a.id) || a.targetX !== null ||
      (a.motion && !['idle', 'sit', 'sleepSit', 'walk', 'talk'].includes(a.motion.name));
  }

  freeSeat(id) {
    const used = new Set([...this.seatOf.entries()].filter(([k]) => k !== id).map(([, v]) => v));
    return this.room.seats.findIndex((_, i) => !used.has(i));
  }

  // The floor position farthest from everyone else (a little random so the room doesn't look staged).
  freeSpot(self) {
    const [x0, x1] = this.room.floorRange;
    const others = this.homeActors().filter((o) => o !== self && !this.seatOf.has(o.id)).map((o) => o.targetX ?? o.x);
    let best = (x0 + x1) / 2, bestD = -1;
    for (let x = x0; x <= x1 + 1e-6; x += 0.1) {
      const d = others.length ? Math.min(...others.map((o) => Math.abs(o - x))) + Math.random() * 0.08 : Math.random();
      if (d > bestD) { bestD = d; best = x; }
    }
    return best;
  }

  get floorCapacity() {
    const [x0, x1] = this.room.floorRange;
    return Math.max(1, Math.floor((x1 - x0) / 0.5) + 1);
  }

  // Place everyone instantly (initial load). If the floor is crowded, some sit on the sofa.
  layout() {
    const [x0, x1] = this.room.floorRange;
    let standing = this.homeActors().filter((a) => !this.seatOf.has(a.id));
    while (standing.length > this.floorCapacity && this.freeSeat() >= 0) {
      const a = standing.pop();
      this.seatOf.set(a.id, this.freeSeat(a.id));
      a.play('sit');
    }
    standing.forEach((a, i) => {
      a.root.position.x = standing.length === 1 ? (x0 + x1) / 2 : x0 + (x1 - x0) * (i / (standing.length - 1));
      a.targetX = null;
    });
    for (const [id, seat] of this.seatOf) {
      const a = this.stage.actors.get(id);
      if (a) a.root.position.x = this.room.seats[seat];
    }
  }

  standUp(a) {
    if (!this.seatOf.has(a.id)) return;
    this.seatOf.delete(a.id);
    a.play('idle');
  }

  sitDown(a, sleep = false) {
    let seat = this.seatOf.get(a.id);
    if (seat == null) seat = this.freeSeat(a.id);
    if (seat < 0) { if (sleep) a.play('sleepy'); return false; }
    this.seatOf.set(a.id, seat);
    const x = this.room.seats[seat];
    const go = () => { a.setFacing(0); a.play(sleep ? 'sleepSit' : 'sit'); };
    if (Math.abs(a.x - x) < 0.05) go();
    else { a.walkTo(x); a.onArrive = go; }
    return true;
  }

  setPresence(id, p, animate = false) {
    // An instant home/away change can race with the animated arrive/leave for the same event:
    // wait a moment, and let the animated one win if it comes.
    const known = this.presence.get(id);
    this.pending = this.pending || new Map();
    if (animate) { clearTimeout(this.pending.get(id)); this.pending.delete(id); }
    else if (known && known.home !== p.home && this.stage.actors.get(id)?.root.visible !== undefined) {
      if (this.pending.has(id)) clearTimeout(this.pending.get(id));
      this.pending.set(id, setTimeout(() => { this.pending.delete(id); this._apply(id, p, false); }, 900));
      return;
    }
    if (!animate && (this.animUntil?.get(id) || 0) > this.clock) return;
    this._apply(id, p, animate);
  }

  _apply(id, p, animate) {
    const a = this.stage.actors.get(id);
    const prev = this.presence.get(id);
    if (animate) { this.animUntil = this.animUntil || new Map(); this.animUntil.set(id, this.clock + 8); }
    this.presence.set(id, p);
    if (!a) return;
    if (!p.home) {
      if (animate && prev && prev.home && a.root.visible) { this.leave(id, p); return; }
      a.root.visible = false;
      this.seatOf.delete(id);
      this.room.away.set(id, { name: a.name, color: a.color, label: p.label || (p.state === 'moving' ? '移動中' : 'おでかけ中'), icon: p.state === 'moving' ? '🚶' : p.state === 'sleep' ? '💤' : '📍' });
      this.stage.bubbles.hide(id, true);
      this.stage.frame();
      return;
    }
    if (animate && prev && !prev.home) { this.arrive(id, p.text); return; }
    this.room.away.delete(id);
    a.root.visible = true;
    if (p.state === 'sleep') this.sitDown(a, true);
    else if (this.seatOf.has(id) && a.motion.name === 'sleepSit') this.standUp(a);
    this.stage.frame();
  }

  arrive(id, text = 'ただいま！') {
    const a = this.stage.actors.get(id);
    if (!a) return;
    this.room.away.delete(id);
    this.room.doorTarget = 1;
    a.roomBusyUntil = this.clock + 6;
    setTimeout(() => {
      a.root.position.x = this.room.doorX;
      a.facingYaw = -Math.PI * 0.42;
      a.root.visible = true;
      this.stage.frame();
      const x = this.freeSpot(a);
      a.walkTo(x, 0.8);
      a.onArrive = () => {
        this.room.doorTarget = 0;
        a.setFacing(0);
        this.api.deliver(id, { text, emotion: 'happy', motion: 'bothWave', fx: 'sparkle', duration: 4200 });
        // everyone awake turns around and waves back
        for (const o of this.homeActors()) {
          if (o === a || o.motion.name === 'sleepSit') continue;
          o.setFacing(Math.sign(a.x - o.x) * 0.5);
          o.sequence([{ motion: 'wave', duration: 1.8 }]);
          setTimeout(() => o.setFacing(0), 2000);
        }
      };
    }, 350);
  }

  leave(id, p) {
    const a = this.stage.actors.get(id);
    if (!a) return;
    this.standUp(a);
    a.roomBusyUntil = this.clock + 8;
    this.api.say(id, { text: (p && p.text) || 'いってきます！', duration: 2200 });
    setTimeout(() => {
      this.stage.bubbles.hide(id, true);
      a.walkTo(this.room.doorX, 0.9);
      a.onArrive = () => {
        this.room.doorTarget = 1;
        setTimeout(() => {
          a.root.visible = false;
          this.room.doorTarget = 0;
          this.room.away.set(id, { name: a.name, color: a.color, label: (p && p.label) || 'おでかけ中', icon: '🚶' });
          this.stage.frame();
        }, 450);
      };
    }, 1600);
  }

  // Idle life: wander, sit, stretch, chat with each other.
  update(dt) {
    this.clock += dt;
    for (const a of this.homeActors()) {
      const p = this.presence.get(a.id) || { home: true, state: 'idle' };
      if (p.state === 'sleep') {
        if (this.clock > (a.nextZ || 0)) { a.nextZ = this.clock + 3.5; this.api.fx('zzz', a.id, 2); }
        if (!this.busy(a) && a.motion.name !== 'sleepSit' && a.targetX === null) this.sitDown(a, true);
        continue;
      }
      if (this.busy(a)) continue;
      if (this.seatOf.has(a.id) && a.motion.name === 'idle') a.play('sit');
      if (!a.nextAct) a.nextAct = this.clock + 2 + Math.random() * 6;
      if (this.clock < a.nextAct) continue;
      a.nextAct = this.clock + (p.state === 'moving' ? 4 : 7) + Math.random() * 8;
      const r = Math.random();
      if (this.seatOf.has(a.id)) {
        if (r < 0.5) { this.standUp(a); a.walkTo(this.freeSpot(a)); }
        continue;
      }
      const crowded = this.homeActors().filter((o) => !this.seatOf.has(o.id)).length > this.floorCapacity;
      if (crowded && this.freeSeat(a.id) >= 0) this.sitDown(a, false);
      else if (r < (p.state === 'moving' ? 0.7 : 0.38)) a.walkTo(this.freeSpot(a), 0.6);
      else if (r < 0.55) this.sitDown(a, false);
      else if (r < 0.68) a.sequence([{ motion: 'stretch' }]);
      else if (r < 0.88) this.chat(a);
      else a.sequence([{ motion: 'lookAround' }]);
    }
  }

  chat(a) {
    const others = this.homeActors().filter((o) => o !== a && !this.busy(o) && o.motion.name !== 'sleepSit' && !this.seatOf.has(o.id));
    if (!others.length) { a.sequence([{ motion: 'lookAround' }]); return; }
    const o = others.reduce((m, x) => (Math.abs(x.x - a.x) < Math.abs(m.x - a.x) ? x : m));
    const dir = Math.sign(o.x - a.x) || 1;
    a.setFacing(dir * 0.9); o.setFacing(-dir * 0.9);
    a.roomBusyUntil = o.roomBusyUntil = this.clock + 4.5;
    a.play('talk'); a.speaking = true;
    setTimeout(() => { a.speaking = false; o.play('talk'); o.speaking = true; }, 1800);
    setTimeout(() => {
      o.speaking = false;
      for (const x of [a, o]) { x.setFacing(0); x.play('nod'); }
      this.api.fx('notes', a.id, 2);
    }, 3800);
  }
}
