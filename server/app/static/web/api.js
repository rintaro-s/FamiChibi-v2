// REST + WebSocket client
const TOKEN_KEY = 'famichibi.token';

export const auth = {
  get token() { try { return localStorage.getItem(TOKEN_KEY) || ''; } catch { return ''; } },
  set token(v) { try { v ? localStorage.setItem(TOKEN_KEY, v) : localStorage.removeItem(TOKEN_KEY); } catch { /* ignore */ } },
};

export class ApiError extends Error {
  constructor(status, message) { super(message); this.status = status; }
}

export async function api(path, { method = 'GET', body, form } = {}) {
  const headers = {};
  if (auth.token) headers.Authorization = `Bearer ${auth.token}`;
  let payload;
  if (form) payload = form;
  else if (body !== undefined) { headers['Content-Type'] = 'application/json'; payload = JSON.stringify(body); }
  const r = await fetch(path, { method, headers, body: payload });
  let data = null;
  try { data = await r.json(); } catch { /* empty */ }
  if (!r.ok) {
    let msg = data && data.detail;
    if (Array.isArray(msg)) msg = msg.map((d) => d.msg).join(', ');
    throw new ApiError(r.status, msg || `エラー (${r.status})`);
  }
  return data;
}

export const get = (p) => api(p);
export const post = (p, body) => api(p, { method: 'POST', body: body ?? {} });
export const patch = (p, body) => api(p, { method: 'PATCH', body });
export const del = (p) => api(p, { method: 'DELETE' });

// Reconnecting WebSocket with ping.
export class Socket {
  constructor(onEvent, onState) {
    this.onEvent = onEvent;
    this.onState = onState || (() => {});
    this.ws = null;
    this.backoff = 1000;
    this.closed = false;
    this.queue = [];
  }
  connect() {
    this.closed = false;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    const ws = new WebSocket(`${proto}://${location.host}/ws?token=${encodeURIComponent(auth.token)}`);
    this.ws = ws;
    ws.onopen = () => {
      this.backoff = 1000;
      this.onState('open');
      for (const m of this.queue.splice(0)) ws.send(m);
      clearInterval(this.ping);
      this.ping = setInterval(() => this.send({ type: 'ping' }), 25000);
    };
    ws.onmessage = (e) => {
      let ev;
      try { ev = JSON.parse(e.data); } catch { return; }
      try { this.onEvent(ev); } catch (err) { console.warn('event handler failed', ev.type, err); }
    };
    ws.onclose = (e) => {
      clearInterval(this.ping);
      this.onState('closed', e.code);
      if (this.closed || e.code === 4401) return;
      setTimeout(() => this.connect(), this.backoff);
      this.backoff = Math.min(this.backoff * 1.7, 15000);
    };
    ws.onerror = () => { /* onclose follows */ };
  }
  send(obj) {
    const m = JSON.stringify(obj);
    if (this.ws && this.ws.readyState === 1) this.ws.send(m);
    else if (obj.type !== 'ping' && obj.type !== 'typing') this.queue.push(m);
  }
  close() { this.closed = true; clearInterval(this.ping); if (this.ws) this.ws.close(); }
}
