// "Living room" stage: all family chibis standing in a row, delivering messages with TTS.

const EMOJI_RE = /[\p{Extended_Pictographic}\u{1F1E6}-\u{1F1FF}‍️]/gu;

export class Living {
  constructor(root, statusEl) {
    this.root = root;
    this.statusEl = statusEl;
    this.ready = false;
    this.loading = new Map(); // id -> promise
    this.key = '';
    this.tts = true;
    this.voices = [];
    this.queue = [];
    this.speakingId = null;
    if ('speechSynthesis' in window) {
      const load = () => { this.voices = speechSynthesis.getVoices().filter((v) => /ja/i.test(v.lang)); };
      load();
      speechSynthesis.onvoiceschanged = load;
    }
  }

  init() {
    if (this.ready || !window.Chibi) return;
    window.Chibi.init({ root: this.root, layout: 'room', modelFraction: 0.58, fps: 30 });
    this.ready = true;
    const ro = new ResizeObserver(() => { if (this.root.clientHeight > 0) window.Chibi.resize(); });
    ro.observe(this.root);
  }

  // members: [{id, url, name, color, ai}]
  async setCast(members) {
    this.init();
    if (!this.ready) return;
    const key = members.map((m) => `${m.id}|${m.url}|${m.name}|${m.color}`).join(',');
    if (key === this.key) return;
    this.key = key;
    const ids = members.map((m) => m.id);
    for (const id of window.Chibi.ids()) if (!ids.includes(id)) window.Chibi.remove(id);
    window.Chibi.room({ members: members.length });
    this.presence(members);
    this._status(`読み込み中… (${members.length}人)`);
    await Promise.all(members.map((m) => this._add(m)));
    if (this.key !== key) return;
    window.Chibi.arrangeRow(ids.filter((id) => window.Chibi.has(id)));
    this._status('');
  }

  // members: [{id, presence:{at_home, state, label}}] - who is home / asleep / out (the living room shows it)
  presence(members, animate = false) {
    if (!this.ready) return;
    for (const m of members) {
      if (m.ai || !m.presence) continue;
      const p = m.presence;
      window.Chibi.presence(m.id, { home: p.at_home !== false, state: p.state || 'idle', label: p.label || '' }, animate);
    }
  }

  homeChange(id, home, presence, voice) {
    if (!this.ready) return;
    const p = presence || {};
    window.Chibi.presence(id, { home, state: p.state || 'idle', label: p.label || '', text: home ? 'ただいま！' : 'いってきます！' }, true);
    if (this.tts && voice) this.speak(id, `${voice.name}、${home ? 'おかえり！' : 'いってらっしゃい！'}`, 1.15);
  }

  lights(on) { if (this.ready) window.Chibi.lights(on); }

  _add(m) {
    const p = window.Chibi.add(m.id, m.url, { name: m.name, color: m.color, ai: m.ai, motion: 'bothWave' }).then((ok) => {
      if (!ok) console.warn('chibi load failed', m.id);
      return ok;
    });
    this.loading.set(m.id, p);
    return p;
  }

  _status(t) { if (this.statusEl) this.statusEl.textContent = t; }

  async whenLoaded(id) {
    if (window.Chibi && window.Chibi.has(id)) return true;
    const p = this.loading.get(id);
    return p ? p : false;
  }

  async deliver(id, opts, voice) {
    if (!this.ready) return false;
    const ok = await this.whenLoaded(id);
    if (!ok || !window.Chibi.has(id)) return false;
    window.Chibi.deliver(id, {
      text: opts.text, emotion: opts.emotion, motion: opts.motion, fx: opts.fx ?? undefined, urgent: !!opts.urgent,
      replies: opts.replies || [], reasons: opts.reasons || [], ref: opts.ref, duration: opts.duration, sticky: opts.sticky,
      badge: opts.badge,
    });
    if (voice && this.tts) this.speak(id, opts.text, voice.pitch);
    return true;
  }

  speak(id, text, pitch = 1) {
    if (!('speechSynthesis' in window) || !text) return;
    const clean = String(text).replace(EMOJI_RE, '').replace(/https?:\/\/\S+/g, 'リンク').trim();
    if (!clean) return;
    const u = new SpeechSynthesisUtterance(clean.slice(0, 200));
    u.lang = 'ja-JP';
    if (this.voices[0]) u.voice = this.voices[0];
    u.pitch = Math.max(0.1, Math.min(2, pitch || 1));
    u.rate = 1.08;
    u.onstart = () => { this.speakingId = id; window.Chibi.speaking(id, true); };
    const end = () => { window.Chibi.speaking(id, false); if (this.speakingId === id) this.speakingId = null; };
    u.onend = end;
    u.onerror = end;
    speechSynthesis.speak(u);
  }

  stopSpeech() {
    if ('speechSynthesis' in window) speechSynthesis.cancel();
    if (this.speakingId && window.Chibi) window.Chibi.speaking(this.speakingId, false);
  }

  react(id, motion, fx) {
    if (!this.ready || !window.Chibi.has(id)) return;
    window.Chibi.play(id, motion, { restart: true });
    if (fx) window.Chibi.fx(fx, id, 6);
  }

  // messages: [{from, text, pitch, name}]
  async party(title, messages, bannerEl) {
    if (!this.ready) return;
    if (bannerEl) {
      bannerEl.textContent = title || '🎉 パーティー！';
      bannerEl.hidden = false;
      clearTimeout(this._bannerT);
      this._bannerT = setTimeout(() => { bannerEl.hidden = true; }, 8000 + messages.length * 4500);
    }
    window.Chibi.party();
    let delay = 2600;
    for (const m of messages) {
      setTimeout(() => {
        if (!window.Chibi.has(m.from)) return;
        window.Chibi.say(m.from, { text: m.text, duration: 6500, replies: ['ありがとう！🎂'], ref: 'party' });
        window.Chibi.fx('hearts', m.from, 6);
        if (this.tts) this.speak(m.from, m.text, m.pitch);
      }, delay);
      delay += 4500;
    }
    setTimeout(() => window.Chibi.fx('confetti'), delay);
  }
}
