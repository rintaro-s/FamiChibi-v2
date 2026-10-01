// Audio-only WebRTC mesh for voice channels. Signaling over the FamiChibi WebSocket:
//   -> {type:'voice_join', channel_id} / {type:'voice_leave'}
//   <- {type:'voice_state', channel_id, users}
//   <-> {type:'rtc', to|from, channel_id, data:{kind:'offer'|'answer'|'ice', sdp?, candidate?}}
// The peer with the smaller user id (string compare) creates the offer.

const ICE = [{ urls: 'stun:stun.l.google.com:19302' }];

export class VoiceRoom {
  constructor({ send, myId, onChange }) {
    this.send = send;
    this.myId = myId;
    this.onChange = onChange || (() => {});
    this.cid = null;
    this.peers = new Map(); // uid -> {pc, audio, pending: [], analyser}
    this.users = [];
    this.levels = new Map(); // uid -> level 0..1
    this.muted = false;
    this.stream = null;
    this.ctx = null;
  }

  get active() { return !!this.cid; }

  static supported() {
    return !!(window.isSecureContext && navigator.mediaDevices && navigator.mediaDevices.getUserMedia && window.RTCPeerConnection);
  }

  async join(cid) {
    if (this.cid === cid) return;
    if (this.cid) this.leave();
    this.stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: true, noiseSuppression: true }, video: false });
    this.cid = cid;
    this.ctx = new (window.AudioContext || window.webkitAudioContext)();
    this._watch(this.myId, this.stream);
    this.send({ type: 'voice_join', channel_id: cid });
    this.timer = setInterval(() => this._poll(), 150);
    this.onChange();
  }

  leave() {
    if (!this.cid) return;
    this.send({ type: 'voice_leave' });
    for (const uid of [...this.peers.keys()]) this._drop(uid);
    if (this.stream) this.stream.getTracks().forEach((t) => t.stop());
    this.stream = null;
    clearInterval(this.timer);
    if (this.ctx) this.ctx.close().catch(() => {});
    this.ctx = null;
    this.analysers = null;
    this.cid = null;
    this.users = [];
    this.levels.clear();
    this.onChange();
  }

  toggleMute() {
    this.muted = !this.muted;
    if (this.stream) this.stream.getAudioTracks().forEach((t) => { t.enabled = !this.muted; });
    this.onChange();
  }

  onVoiceState(ev) {
    if (!this.cid || ev.channel_id !== this.cid) return;
    this.users = ev.users || [];
    for (const uid of this.users) {
      if (uid === this.myId || this.peers.has(uid)) continue;
      if (this.myId < uid) this._call(uid);
    }
    for (const uid of [...this.peers.keys()]) if (!this.users.includes(uid)) this._drop(uid);
    this.onChange();
  }

  async onRtc(ev) {
    if (!this.cid || (ev.channel_id && ev.channel_id !== this.cid)) return;
    const d = ev.data || {};
    const uid = ev.from;
    try {
      if (d.kind === 'offer') {
        const p = this._peer(uid);
        await p.pc.setRemoteDescription({ type: 'offer', sdp: d.sdp });
        await this._flush(p);
        const ans = await p.pc.createAnswer();
        await p.pc.setLocalDescription(ans);
        this._signal(uid, { kind: 'answer', sdp: ans.sdp });
      } else if (d.kind === 'answer') {
        const p = this.peers.get(uid);
        if (p) { await p.pc.setRemoteDescription({ type: 'answer', sdp: d.sdp }); await this._flush(p); }
      } else if (d.kind === 'ice' && d.candidate) {
        const p = this._peer(uid);
        if (p.pc.remoteDescription) await p.pc.addIceCandidate(d.candidate);
        else p.pending.push(d.candidate);
      }
    } catch (e) { console.warn('rtc', e); }
  }

  async _flush(p) {
    for (const c of p.pending.splice(0)) { try { await p.pc.addIceCandidate(c); } catch { /* ignore */ } }
  }

  _signal(to, data) { this.send({ type: 'rtc', to, channel_id: this.cid, data }); }

  _peer(uid) {
    let p = this.peers.get(uid);
    if (p) return p;
    const pc = new RTCPeerConnection({ iceServers: ICE });
    const audio = new Audio();
    audio.autoplay = true;
    p = { pc, audio, pending: [] };
    this.peers.set(uid, p);
    if (this.stream) this.stream.getTracks().forEach((t) => pc.addTrack(t, this.stream));
    pc.onicecandidate = (e) => {
      if (e.candidate) this._signal(uid, { kind: 'ice', candidate: { candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex } });
    };
    pc.ontrack = (e) => {
      const s = e.streams[0] || new MediaStream([e.track]);
      audio.srcObject = s;
      audio.play().catch(() => {});
      this._watch(uid, s);
    };
    pc.onconnectionstatechange = () => this.onChange();
    return p;
  }

  async _call(uid) {
    const p = this._peer(uid);
    const offer = await p.pc.createOffer({ offerToReceiveAudio: true });
    await p.pc.setLocalDescription(offer);
    this._signal(uid, { kind: 'offer', sdp: offer.sdp });
  }

  _drop(uid) {
    const p = this.peers.get(uid);
    if (!p) return;
    try { p.pc.close(); } catch { /* ignore */ }
    p.audio.srcObject = null;
    this.peers.delete(uid);
    if (this.analysers) this.analysers.delete(uid);
    this.levels.delete(uid);
  }

  _watch(uid, stream) {
    if (!this.ctx) return;
    try {
      const src = this.ctx.createMediaStreamSource(stream);
      const an = this.ctx.createAnalyser();
      an.fftSize = 512;
      src.connect(an);
      this.analysers = this.analysers || new Map();
      this.analysers.set(uid, { an, buf: new Uint8Array(an.fftSize) });
    } catch (e) { /* ignore */ }
  }

  _poll() {
    if (!this.analysers) return;
    let changed = false;
    for (const [uid, { an, buf }] of this.analysers) {
      an.getByteTimeDomainData(buf);
      let sum = 0;
      for (const v of buf) { const x = (v - 128) / 128; sum += x * x; }
      const lvl = Math.min(1, Math.sqrt(sum / buf.length) * 6);
      const prev = this.levels.get(uid) || 0;
      if ((prev > 0.15) !== (lvl > 0.15)) changed = true;
      this.levels.set(uid, lvl);
    }
    if (changed) this.onChange('levels');
  }

  speaking(uid) { return (this.levels.get(uid) || 0) > 0.15 && !(uid === this.myId && this.muted); }
  state(uid) { const p = this.peers.get(uid); return p ? p.pc.connectionState : (uid === this.myId ? 'connected' : 'new'); }
}
