import { api, auth, get, post, patch, del, Socket, ApiError } from './api.js';
import { Living } from './chibi.js';
import { VoiceRoom } from './voice.js';

// ---------------------------------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------------------------------
const $ = (s, el = document) => el.querySelector(s);
const $$ = (s, el = document) => [...el.querySelectorAll(s)];
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
const WD = '日月火水木金土';
const EMO = { happy: '😊 うれしい', love: '💕 愛情', excited: '🎉 大喜び', thanks: '🙏 感謝', sad: '😢 かなしい', angry: '💢 おこ',
  surprised: '😲 びっくり', worried: '💦 心配', sleepy: '💤 ねむい' };
const MEMO_COLORS = ['#fff3a3', '#ffd6e0', '#cdeffd', '#d5f5c8', '#ffe0b8', '#e6dcff'];

function toast(text, err = false) {
  const t = document.createElement('div');
  t.className = 'toast' + (err ? ' err' : '');
  t.textContent = text;
  $('#toasts').appendChild(t);
  setTimeout(() => t.remove(), 3800);
}
function fail(e) { console.warn(e); toast(e instanceof ApiError ? e.message : 'エラーが発生しました', true); }
function fmtTime(ts) { const d = new Date(ts * 1000); return `${d.getHours()}:${String(d.getMinutes()).padStart(2, '0')}`; }
function fmtDay(ts) { const d = new Date(ts * 1000); return `${d.getMonth() + 1}/${d.getDate()}(${WD[d.getDay()]})`; }
function initial(name) { return (name || '?').trim().slice(0, 1); }
function debounce(fn, ms) { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; }

function dialog(title, inner, okLabel = 'OK') {
  const dlg = $('#dialog');
  const form = $('#dialog-form');
  form.innerHTML = `<h3>${esc(title)}</h3>${inner}<div class="acts"><button value="cancel" class="btn" formnovalidate>キャンセル</button><button value="ok" class="btn primary">${esc(okLabel)}</button></div>`;
  return new Promise((resolve) => {
    dlg.onclose = () => resolve(dlg.returnValue === 'ok' ? new FormData(form) : null);
    dlg.showModal();
    const first = form.querySelector('input,select,textarea');
    if (first) first.focus();
  });
}

// ---------------------------------------------------------------------------------------------
// state
// ---------------------------------------------------------------------------------------------
const S = {
  user: null, families: [], fid: null, cid: null, tab: 'chat', avatars: new Map(), avatarList: [],
  messages: new Map(), home: null, voiceUsers: {}, memos: null, events: null, tasks: null, asks: new Map(),
  typing: new Map(), calMonth: new Date(), calDay: null, scenarios: null, memoColor: MEMO_COLORS[0],
};
const fam = () => S.families.find((f) => f.id === S.fid) || null;
const channel = () => (fam()?.channels || []).find((c) => c.id === S.cid) || null;
const living = new Living($('#stage'), $('#stage-status'));
let socket = null;
let voice = null;

function member(uid, f = fam()) {
  if (!f) return null;
  if (!uid || uid === 'ai') return f.ai || { user_id: 'ai', name: f.ai_name, nickname: f.ai_name, color: '#5ec8ff', ai: true, voice_pitch: 1.25 };
  return f.members.find((m) => m.user_id === uid) || null;
}
const label = (m) => (m ? (m.nickname || m.name) : 'だれか');
function avatarUrl(id) {
  const a = S.avatars.get(id) || S.avatarList[0];
  return a ? a.url : '';
}
function avHtml(m, cls = '') {
  const ai = m && (m.ai || m.user_id === 'ai');
  const online = m && !ai ? `<i class="on ${m.online ? 'yes' : ''}"></i>` : '';
  return `<span class="av ${ai ? 'ai' : ''} ${cls}" style="background:${esc(m?.color || '#ccc')}">${ai ? '✦' : esc(initial(label(m)))}${online}</span>`;
}

// ---------------------------------------------------------------------------------------------
// views
// ---------------------------------------------------------------------------------------------
function show(id) {
  for (const v of $$('.view')) v.hidden = v.id !== id;
}

function applyTheme(t) {
  document.documentElement.dataset.theme = t;
  try { localStorage.setItem('famichibi.theme', t); } catch { /* ignore */ }
  if (window.Chibi && living.ready) window.Chibi.configure({ theme: t });
}
function toggleTheme() { applyTheme(document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark'); }

async function loadAvatars() {
  const { avatars } = await get('/api/avatars');
  S.avatarList = avatars;
  S.avatars = new Map(avatars.map((a) => [a.id, a]));
}

// ----- onboarding ------------------------------------------------------------------------------
async function showOnboard(invite) {
  show('view-onboard');
  await loadAvatars().catch(() => {});
  let sel = invite?.preset_avatar || S.avatarList[0]?.id || '';
  const grid = $('#ob-avatars');
  const colors = ['#4f7cff', '#2ec4b6', '#b57bff', '#ff8a65', '#ff7a90', '#3cc38a'];
  const draw = () => {
    grid.innerHTML = S.avatarList.map((a, i) => `<button class="avatar-opt ${a.id === sel ? 'sel' : ''}" data-id="${esc(a.id)}">
      <div class="face" style="background:${colors[i % colors.length]}">${esc(initial(a.name))}</div>${esc(a.name)}<small>${a.builtin ? 'サンプル' : 'アップロード'}</small></button>`).join('');
  };
  draw();
  grid.onclick = (e) => { const b = e.target.closest('.avatar-opt'); if (b) { sel = b.dataset.id; draw(); } };
  if (invite) {
    $('#invite-banner').hidden = false;
    $('#invite-banner').innerHTML = `${esc(invite.family.emoji)} <b>${esc(invite.family.name)}</b> に招待されています${invite.preset_nickname ? `（呼び名: ${esc(invite.preset_nickname)}）` : ''}`;
    $('#ob-name').value = invite.preset_name || '';
  }
  $('#ob-go').onclick = async () => {
    const name = $('#ob-name').value.trim();
    if (!name) { toast('名前を入れてね', true); return; }
    try {
      if (invite) {
        const r = await post(`/api/invites/${invite.token}/claim`, { name });
        auth.token = r.token;
        if (sel && sel !== r.user.avatar) await patch('/api/me', { avatar: sel });
        history.replaceState(null, '', '/');
        await boot(r.family.id);
      } else {
        const r = await post('/api/register', { name, avatar: sel });
        auth.token = r.token;
        await boot();
      }
    } catch (e) { fail(e); }
  };
}

// ----- home ------------------------------------------------------------------------------------
function renderHome() {
  show('view-home');
  $('#home-name').textContent = S.user?.name || '';
  const list = $('#family-list');
  if (!S.families.length) {
    list.innerHTML = '<div class="card empty">まだ家族サーバーがありません。作成するか、招待コードで参加しよう！</div>';
  } else {
    list.innerHTML = S.families.map((f) => {
      const unread = Object.values(f.unread || {}).reduce((a, b) => a + b, 0);
      return `<div class="card fam-card" data-id="${esc(f.id)}"><div class="emoji">${esc(f.emoji)}</div>
        <div class="grow"><b>${esc(f.name)}</b> ${unread ? `<span class="badge">${unread}</span>` : ''}
        <div class="muted small">${f.members.length}人 ・ コード ${esc(f.code)}</div>
        <div class="dots">${f.members.map((m) => `<i style="background:${esc(m.color)}" title="${esc(label(m))}"></i>`).join('')}</div></div></div>`;
    }).join('');
  }
  list.onclick = (e) => { const c = e.target.closest('.fam-card'); if (c) openFamily(c.dataset.id); };
}

async function createFamily() {
  const name = $('#cf-name').value.trim();
  if (!name) { toast('家族サーバーの名前を入れてね', true); return; }
  try {
    const { family } = await post('/api/families', { name, emoji: $('#cf-emoji').value || '🏠', nickname: $('#cf-nick').value.trim() });
    S.families.push(family);
    openFamily(family.id);
  } catch (e) { fail(e); }
}
async function joinFamily() {
  const code = $('#jf-code').value.trim();
  if (!code) return;
  try {
    const { family } = await post('/api/families/join', { code, nickname: $('#jf-nick').value.trim() });
    upsertFamily(family);
    openFamily(family.id);
  } catch (e) { fail(e); }
}
async function seedDemo() {
  const b = $('#demo-seed');
  b.disabled = true;
  try {
    const { family } = await post('/api/demo/seed');
    upsertFamily(family);
    openFamily(family.id);
    toast('デモ家族を作りました 🎬 右下のボタンからシナリオを再生できます');
  } catch (e) { fail(e); } finally { b.disabled = false; }
}

function upsertFamily(f) {
  const i = S.families.findIndex((x) => x.id === f.id);
  if (i >= 0) S.families[i] = { ...S.families[i], ...f, me: f.me || S.families[i].me, unread: f.unread || S.families[i].unread };
  else S.families.push(f);
}

// ----- family ----------------------------------------------------------------------------------
async function openFamily(fid) {
  S.fid = fid;
  try { localStorage.setItem('famichibi.fid', fid); } catch { /* ignore */ }
  S.memos = S.events = S.tasks = null;
  const f = fam();
  if (!f) { renderHome(); return; }
  show('view-family');
  renderSidebar();
  // chat history first: the living room's VRMs are big and must not delay it
  const first = f.channels.find((c) => c.kind === 'text');
  const opened = selectChannel(first ? first.id : null);
  syncCast();
  await opened;
  setTab(S.tab);
  if (!S.home) get(`/api/families/${fid}/home`).then((h) => { S.home = h; if (S.tab === 'home') renderHomeTab(); }).catch(() => {});
}

function syncCast() {
  const f = fam();
  if (!f) return;
  const cast = f.members.map((m) => ({ id: m.user_id, url: avatarUrl(m.avatar), name: label(m), color: m.color, presence: m.presence }));
  if (f.ai) cast.push({ id: 'ai', url: avatarUrl(f.ai_avatar), name: f.ai_name, color: '#5ec8ff', ai: true });
  living.setCast(cast.filter((c) => c.url)).catch((e) => console.warn(e));
}

function renderSidebar() {
  const f = fam();
  if (!f) return;
  // "おうち" is an optional extension: only show its tab when it is on
  const homeBtn = $('#tabs button[data-tab="home"]');
  if (homeBtn) homeBtn.hidden = !f.iot_enabled;
  if (!f.iot_enabled && S.tab === 'home') setTab('chat');
  $('#fam-emoji').textContent = f.emoji; $('#fam-name').textContent = f.name;
  $('#fam-emoji-m').textContent = f.emoji; $('#fam-name-m').textContent = f.name;
  $('#fam-code').textContent = f.code;
  $('#channel-list').innerHTML = f.channels.map((c) => {
    const n = (f.unread || {})[c.id] || 0;
    const vu = S.voiceUsers[c.id] || [];
    return `<div class="chan ${c.id === S.cid ? 'active' : ''}" data-id="${esc(c.id)}"><span>${c.kind === 'voice' ? '🔊' : c.name === 'ちびAI' ? '✦' : '#'}</span>
      <span class="n">${esc(c.name)}</span>${c.kind === 'voice' && vu.length ? `<span class="vc">${vu.length}人</span>` : ''}${n && c.id !== S.cid ? `<span class="badge">${n}</span>` : ''}</div>`;
  }).join('');
  const members = [...f.members];
  if (f.ai) members.push(f.ai);
  $('#member-list').innerHTML = members.map((m) => `<div class="member" data-id="${esc(m.user_id)}">${avHtml(m)}
    <div class="meta"><b>${esc(label(m))}</b><small>${m.ai ? 'AIちびっこ' : `${esc(m.name)}${m.role === 'owner' ? ' ・ オーナー' : ''}${m.calendar_private ? ' ・ 🔒' : ''}`}</small></div>
    ${!m.ai && m.user_id !== S.user.id ? `<button class="poke" title="つんつん" data-to="${esc(m.user_id)}">👉</button>` : ''}</div>`).join('');
  renderVoiceMini();
}

async function selectChannel(cid) {
  S.cid = cid;
  const c = channel();
  closeSide();
  if (!c) { renderSidebar(); return; }
  if (c.kind === 'voice') {
    setTab('chat');
    $('#pane-chat').hidden = true;
    $('#pane-voice').hidden = false;
    renderVoicePane();
    renderSidebar();
    return;
  }
  $('#pane-voice').hidden = true;
  if (S.tab !== 'chat') setTab('chat');
  $('#pane-chat').hidden = false;
  $('#chan-name').textContent = (c.name === 'ちびAI' ? '✦ ' : '# ') + c.name;
  $('#msg-in').placeholder = c.name === 'ちびAI' ? 'ちびAIにお願い（例: 土曜みんな空いてる？ / 見逃した連絡まとめて）' : 'メッセージ（@ちびAI で AIに頼む）';
  if (!S.messages.has(cid)) {
    $('#messages').innerHTML = '<div class="empty">読み込み中…</div>';
    try {
      const { messages } = await get(`/api/channels/${cid}/messages?limit=80`);
      S.messages.set(cid, messages);
    } catch (e) { fail(e); S.messages.set(cid, []); }
  }
  if (S.cid !== cid) return;
  renderMessages(true);
  markRead();
  renderSidebar();
}

const markRead = debounce(() => {
  const f = fam();
  const c = channel();
  if (!f || !c || c.kind !== 'text' || S.tab !== 'chat' || document.hidden) return;
  post(`/api/channels/${c.id}/read`).catch(() => {});
  if (f.unread) { f.unread[c.id] = 0; renderSidebar(); }
}, 400);

// ----- chat ------------------------------------------------------------------------------------
function msgHtml(m) {
  const f = fam();
  const mine = m.user_id === S.user.id;
  const sender = member(m.user_id);
  const a = m.analysis || {};
  const isAi = m.kind === 'ai' || !m.user_id;
  if (m.kind === 'system') return `<div class="msg system"><div class="bub">${esc(m.text)}</div></div>`;
  const tags = [];
  if (isAi) tags.push('<span class="tag ai">AI</span>');
  if (a.urgency === 'high') tags.push('<span class="tag urgent">急ぎ</span>');
  if (a.emotion && EMO[a.emotion] && !isAi) tags.push(`<span class="tag emo">${EMO[a.emotion]}</span>`);
  let body = esc(m.text);
  if (m.kind === 'voice' && m.media) body = `🎤 ボイスメッセージ${m.meta?.duration ? ` (${Math.round(m.meta.duration)}秒)` : ''}<br><audio controls preload="none" src="${esc(m.media)}"></audio>`;
  const acks = (m.acks || []).filter((u) => u !== S.user.id && u !== m.user_id);
  const others = (f?.members.length || 1) - 1;
  const ackInfo = mine && acks.length ? `<span>既読 ${acks.length === others ? 'みんな' : acks.length}</span>` : '';
  const needAck = !mine && a.urgency === 'high' && !(m.acks || []).includes(S.user.id);
  const reasons = (a.reasons || []).concat(m.notify?.reason ? [m.notify.reason] : []);
  const cites = m.meta?.citations || [];
  const why = reasons.length || cites.length
    ? `<details class="why"><summary>${isAi ? 'なぜ？（AIの判断根拠）' : 'なぜこの届け方？'}</summary><ul>${reasons.map((r) => `<li>${esc(r)}</li>`).join('')}${cites.map((c) => `<li>📎 ${esc(c.when)} ${esc(c.who)}「${esc(c.text)}」</li>`).join('')}</ul></details>` : '';
  return `<div class="msg ${mine ? 'mine' : ''} ${isAi ? 'ai' : ''} ${a.urgency === 'high' ? 'urgent' : ''}" data-id="${m.id}">
    ${mine ? '' : avHtml(sender || { name: '?', color: '#ccc' })}
    <div class="body">${mine ? '' : `<span class="who">${esc(label(sender))}</span>`}<div class="bub">${body}</div>
    <div class="info">${tags.join('')}<span>${fmtTime(m.created_at)}</span>${ackInfo}${needAck ? `<button class="link-btn ack-btn" data-id="${m.id}">👀 見たよ</button>` : ''}</div>${why}</div></div>`;
}

function renderMessages(scroll = false) {
  const box = $('#messages');
  const list = S.messages.get(S.cid) || [];
  const near = box.scrollHeight - box.scrollTop - box.clientHeight < 120;
  let lastDay = '';
  const parts = [];
  for (const m of list) {
    const d = fmtDay(m.created_at);
    if (d !== lastDay) { parts.push(`<div class="day-sep">${d}</div>`); lastDay = d; }
    parts.push(msgHtml(m));
  }
  box.innerHTML = parts.join('') || `<div class="empty">まだメッセージはありません。${channel()?.name === 'ちびAI' ? '「何ができる？」と聞いてみよう！' : '最初のひとことをどうぞ 👋'}</div>`;
  if (scroll || near) box.scrollTop = box.scrollHeight;
  renderQuick();
}

function renderQuick() {
  const q = $('#quick');
  const c = channel();
  if (!c) { q.innerHTML = ''; return; }
  let chips = [];
  if (c.name === 'ちびAI') chips = ['何ができる？', ...(fam()?.iot_enabled ? ['電気消して'] : []), '今度の土曜日、みんな空いてる？', '見逃した連絡まとめて'];
  else {
    const list = S.messages.get(S.cid) || [];
    const last = list[list.length - 1];
    if (last && last.user_id && last.user_id !== S.user.id) chips = (last.analysis?.replies || []).slice(0, 4);
  }
  q.innerHTML = chips.map((t) => `<button type="button">${esc(t)}</button>`).join('');
}

async function sendText(text, cid = S.cid) {
  text = text.trim();
  if (!text || !cid) return;
  try {
    await post(`/api/channels/${cid}/messages`, { text });
  } catch (e) { fail(e); }
}

let lastTyping = 0;
function onTyping() {
  if (Date.now() - lastTyping > 2500 && S.cid) { lastTyping = Date.now(); socket?.send({ type: 'typing', channel_id: S.cid }); }
}
function renderTyping() {
  const now = Date.now();
  const names = [...S.typing.entries()].filter(([k, v]) => v.cid === S.cid && now - v.at < 4000).map(([uid]) => label(member(uid)));
  $('#typing').textContent = names.length ? `${names.join('・')}が入力中…` : '';
}
setInterval(renderTyping, 1000);

// voice message recording
let recorder = null;
async function toggleRecord() {
  const btn = $('#rec-btn');
  if (recorder) { recorder.stop(); return; }
  if (!navigator.mediaDevices?.getUserMedia) { toast('この環境では録音できません（HTTPSまたはlocalhostが必要）', true); return; }
  try {
    const stream = await navigator.mediaDevices.getUserMedia({ audio: true });
    const chunks = [];
    const started = Date.now();
    recorder = new MediaRecorder(stream);
    recorder.ondataavailable = (e) => chunks.push(e.data);
    recorder.onstop = async () => {
      stream.getTracks().forEach((t) => t.stop());
      btn.classList.remove('rec'); btn.textContent = '🎤';
      recorder = null;
      const blob = new Blob(chunks, { type: chunks[0]?.type || 'audio/webm' });
      const dur = (Date.now() - started) / 1000;
      if (dur < 0.6) return;
      const fd = new FormData();
      fd.append('file', blob, blob.type.includes('ogg') ? 'voice.ogg' : 'voice.webm');
      fd.append('duration', String(dur));
      try { await api(`/api/channels/${S.cid}/voice`, { method: 'POST', form: fd }); } catch (e) { fail(e); }
    };
    recorder.start();
    btn.classList.add('rec'); btn.textContent = '■';
    toast('録音中… もう一度押すと送信');
  } catch (e) { fail(e); }
}

// ----- tabs --------------------------------------------------------------------------------------
function setTab(tab) {
  S.tab = tab;
  for (const b of $$('#tabs button')) b.classList.toggle('active', b.dataset.tab === tab);
  const isVoice = channel()?.kind === 'voice';
  $('#pane-chat').hidden = tab !== 'chat' || isVoice;
  $('#pane-voice').hidden = tab !== 'chat' || !isVoice;
  for (const t of ['memo', 'cal', 'task', 'home', 'settings']) $(`#pane-${t}`).hidden = tab !== t;
  if (tab === 'chat' && !isVoice) { renderMessages(true); markRead(); }
  if (tab === 'memo') renderMemos(true);
  if (tab === 'cal') renderCal(true);
  if (tab === 'task') renderTasks(true);
  if (tab === 'home') renderHomeTab(true);
  if (tab === 'settings') renderSettings();
}

// ----- fridge memos -------------------------------------------------------------------------------
async function renderMemos(load = false) {
  const pane = $('#pane-memo');
  const f = fam();
  if (load || !S.memos) {
    if (!S.memos) pane.innerHTML = '<div class="empty">読み込み中…</div>';
    try { S.memos = (await get(`/api/families/${f.id}/memos`)).memos; } catch (e) { fail(e); S.memos = []; }
  }
  const input = $('#memo-text');
  const keep = input ? input.value : '';
  if (!$('#memo-form', pane)) {
    pane.innerHTML = `<div class="fridge"><form id="memo-form" class="memo-input">
      <input id="memo-text" placeholder="例: 15日16時：カレー作っておいたから食べてね" autocomplete="off">
      <select id="memo-target"></select>
      <div class="row">${MEMO_COLORS.map((c) => `<span class="color-dot" data-c="${c}" style="background:${c}"></span>`).join('')}</div>
      <button class="btn primary">貼る</button></form>
      <div class="memo-preview" id="memo-preview">日時を書くと、その時刻にちびっこが踊りながら知らせます 💃</div>
      <div class="memo-grid" id="memo-grid"></div></div>`;
    $('#memo-text').value = keep;
  }
  $('#memo-target').innerHTML = `<option value="">みんなへ</option>${f.members.map((m) => `<option value="${esc(m.user_id)}">${esc(label(m))}へ</option>`).join('')}`;
  for (const d of $$('.color-dot', pane)) d.classList.toggle('sel', d.dataset.c === S.memoColor);
  const grid = $('#memo-grid');
  if (!S.memos.length) { grid.innerHTML = '<div class="empty" style="grid-column:1/-1">冷蔵庫はまだまっさら。メモを貼ってみよう🧲</div>'; return; }
  grid.innerHTML = S.memos.map((m, i) => {
    const author = member(m.author_id);
    const target = m.target_id ? member(m.target_id) : null;
    return `<div class="memo ${m.done ? 'done' : ''}" style="background:${esc(m.color)};--rot:${((i * 37) % 7) - 3}deg" data-id="${esc(m.id)}">
      <div class="mt">${esc(m.text)}</div>
      <div class="mm">✍ ${esc(label(author))}${target ? ` → ${esc(label(target))}` : ''}${m.notify_label ? ` ・ ⏰ ${esc(m.notify_label)}${m.delivered ? ' ✓' : ''}` : ''}</div>
      <div class="acts"><button data-act="done">${m.done ? '戻す' : '済'}</button><button data-act="del">🗑</button></div></div>`;
  }).join('');
}

const memoPreview = debounce(async () => {
  const t = $('#memo-text')?.value.trim();
  const box = $('#memo-preview');
  if (!box) return;
  if (!t) { box.textContent = '日時を書くと、その時刻にちびっこが踊りながら知らせます 💃'; return; }
  try {
    const r = await post('/api/memos/parse', { text: t });
    box.innerHTML = r.notify_at ? `⏰ <b>${esc(r.label)}</b> にちびっこがお知らせ ・ 本文「${esc(r.text)}」${EMO[r.emotion] ? ` ・ ${EMO[r.emotion]}` : ''}`
      : '日時は見つかりませんでした（通知なしで貼ります）';
  } catch { /* ignore */ }
}, 350);

// ----- calendar ------------------------------------------------------------------------------------
async function renderCal(load = false) {
  const pane = $('#pane-cal');
  const f = fam();
  const m0 = new Date(S.calMonth.getFullYear(), S.calMonth.getMonth(), 1);
  const start = new Date(m0); start.setDate(1 - m0.getDay());
  const end = new Date(start); end.setDate(start.getDate() + 42);
  if (load || !S.events) {
    try { S.events = (await get(`/api/families/${f.id}/events?start=${start.getTime() / 1000}&end=${end.getTime() / 1000}`)).events; } catch (e) { fail(e); S.events = []; }
  }
  const byDay = new Map();
  for (const e of S.events) {
    const d = new Date(e.start * 1000);
    const k = d.toDateString();
    if (!byDay.has(k)) byDay.set(k, []);
    byDay.get(k).push(e);
  }
  const today = new Date().toDateString();
  const cells = [];
  for (let i = 0; i < 42; i++) {
    const d = new Date(start); d.setDate(start.getDate() + i);
    const evs = byDay.get(d.toDateString()) || [];
    cells.push(`<div class="cal-cell ${d.getMonth() !== m0.getMonth() ? 'other' : ''} ${d.toDateString() === today ? 'today' : ''}" data-d="${d.getTime()}">
      <div class="d">${d.getDate()}</div>${evs.slice(0, 3).map((e) => evChip(e)).join('')}${evs.length > 3 ? `<div class="muted">+${evs.length - 3}</div>` : ''}</div>`);
  }
  const sel = S.calDay ? new Date(S.calDay) : null;
  const dayEvs = sel ? (byDay.get(sel.toDateString()) || []) : [];
  pane.innerHTML = `<div class="cal-head"><button class="icon-btn" data-nav="-1">‹</button><h3>${m0.getFullYear()}年${m0.getMonth() + 1}月</h3>
    <button class="icon-btn" data-nav="1">›</button><div class="grow"></div><button class="btn primary" id="ev-add">＋ 予定</button></div>
    <div class="cal-grid">${[...WD].map((w) => `<div class="cal-dow">${w}</div>`).join('')}${cells.join('')}</div>
    ${sel ? `<div class="day-list"><h4>${sel.getMonth() + 1}/${sel.getDate()}(${WD[sel.getDay()]}) の予定</h4>${dayEvs.length ? dayEvs.map((e) => {
      const m = e.user_id ? member(e.user_id) : null;
      return `<div class="ev-row">${m ? avHtml(m) : '<span class="av" style="background:#999">家</span>'}<div class="grow"><b>${esc(e.masked ? '予定あり（非公開）' : e.title)}</b>
        <div class="muted small">${e.all_day ? '終日' : `${fmtTime(e.start)}${e.end ? `〜${fmtTime(e.end)}` : ''}`} ・ ${m ? esc(label(m)) : '家族の予定'}</div></div>
        ${!e.user_id || e.user_id === S.user.id ? `<button class="icon-btn" data-del-ev="${esc(e.id)}">🗑</button>` : ''}</div>`;
    }).join('') : '<div class="muted">予定はありません</div>'}</div>` : '<p class="muted small">日付をクリックすると詳細が見られます。鍵付きの人の予定は「予定あり」とだけ表示されます 🔒</p>'}`;
}
function evChip(e) {
  const m = e.user_id ? member(e.user_id) : null;
  return `<div class="ev ${e.masked ? 'masked' : ''}" style="background:${esc(m?.color || '#8a8a8a')}" title="${esc(e.title)}">${e.masked ? '予定あり' : esc(e.title)}</div>`;
}
async function addEvent(day) {
  const d = day ? new Date(day) : new Date();
  const ds = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
  const fd = await dialog('予定を追加', `<input name="title" placeholder="タイトル" required>
    <div class="row"><input type="date" name="date" value="${ds}" required><input type="time" name="start" value="10:00"><span>〜</span><input type="time" name="end" value="11:00"></div>
    <label class="row"><input type="checkbox" name="shared"> 家族みんなの予定</label><label class="row"><input type="checkbox" name="private"> この予定だけ非公開</label>`, '追加');
  if (!fd) return;
  const [y, mo, da] = fd.get('date').split('-').map(Number);
  const mk = (t) => { const [h, mi] = (t || '00:00').split(':').map(Number); return new Date(y, mo - 1, da, h, mi).getTime() / 1000; };
  try {
    await post(`/api/families/${S.fid}/events`, { title: fd.get('title'), start: mk(fd.get('start')), end: fd.get('end') ? mk(fd.get('end')) : null,
      shared: !!fd.get('shared'), private: !!fd.get('private') });
    S.calDay = new Date(y, mo - 1, da).getTime();
    renderCal(true);
  } catch (e) { fail(e); }
}

// ----- tasks --------------------------------------------------------------------------------------
async function renderTasks(load = false) {
  const pane = $('#pane-task');
  const f = fam();
  if (load || !S.tasks) {
    try { S.tasks = (await get(`/api/families/${f.id}/tasks`)).tasks; } catch (e) { fail(e); S.tasks = []; }
  }
  const opts = (sel) => `<option value="">だれでも</option>${f.members.map((m) => `<option value="${esc(m.user_id)}" ${m.user_id === sel ? 'selected' : ''}>${esc(label(m))}</option>`).join('')}`;
  const keep = $('#task-title')?.value || '';
  pane.innerHTML = `<form class="task-add" id="task-form"><input id="task-title" placeholder="やること（例: お風呂掃除）" autocomplete="off"><select id="task-assignee">${opts('')}</select><button class="btn primary">追加</button></form>
    ${S.tasks.length ? S.tasks.map((t) => `<div class="task ${t.done ? 'done' : ''}" data-id="${esc(t.id)}"><input type="checkbox" ${t.done ? 'checked' : ''} data-act="done">
      <span class="tt">${esc(t.title)}</span><select data-act="assign">${opts(t.assignee_id)}</select><button class="icon-btn" data-act="del">🗑</button></div>`).join('')
    : '<div class="empty">やることはありません。えらい！✨</div>'}
    <p class="muted small">完了にすると、頼んだ人のスマホにちびっこが「やっておいたよ！」と伝えに行きます。</p>`;
  $('#task-title').value = keep;
}

// ----- virtual home --------------------------------------------------------------------------------
const DEV_ICON = { light: '💡', climate: '❄️', cover: '🪟', lock: '🔒', sensor: '📟' };
async function renderHomeTab(load = false) {
  const pane = $('#pane-home');
  const f = fam();
  if (load || !S.home) {
    try { S.home = await get(`/api/families/${f.id}/home`); } catch (e) { fail(e); }
  }
  if (!S.home) return;
  const rooms = new Map();
  for (const [id, d] of Object.entries(S.home.devices)) {
    if (!rooms.has(d.room)) rooms.set(d.room, []);
    rooms.get(d.room).push([id, d]);
  }
  const dev = ([id, d]) => {
    let ic = DEV_ICON[d.type] || '🔌';
    let ctrl = '';
    let cls = '';
    if (d.type === 'light') { cls = d.on ? 'glow' : 'off'; ctrl = sw(id, 'on', d.on); }
    else if (d.type === 'climate') { ic = d.mode === 'heat' ? '🔥' : '❄️'; cls = d.on ? '' : 'off'; ctrl = `<span class="val">${d.on ? `${esc(d.target)}℃` : ''}</span>${sw(id, 'on', d.on)}`; }
    else if (d.type === 'cover') { ic = d.open ? '🪟' : '🎭'; ctrl = `<span class="muted small">${d.open ? '開' : '閉'}</span>${sw(id, 'open', d.open)}`; }
    else if (d.type === 'lock') { ic = d.locked ? '🔒' : '🔓'; ctrl = `<span class="muted small">${d.locked ? '施錠' : '解錠'}</span>${sw(id, 'locked', d.locked)}`; }
    else ctrl = `<span class="val">${esc(d.value)}${esc(d.unit || '')}</span>`;
    return `<div class="dev"><span class="ic ${cls}">${ic}</span><span class="nm">${esc(d.name)}</span>${ctrl}</div>`;
  };
  const sw = (id, key, v) => `<label class="switch"><input type="checkbox" data-dev="${esc(id)}" data-key="${key}" ${v ? 'checked' : ''} ${f.iot_enabled ? '' : 'disabled'}><span></span></label>`;
  pane.innerHTML = `<p class="muted small home-note">🏠 デモ用の仮想ハウス（MCPサーバー経由）。ちびAIに「電気消して」と頼むと、ここがリアルタイムに変わります。${f.iot_enabled ? '' : '<b>IoT連携はOFFです</b>'}</p>
    <div class="house">${[...rooms.entries()].map(([room, devs]) => {
      const lights = devs.filter(([, d]) => d.type === 'light');
      const dark = lights.length && lights.every(([, d]) => !d.on);
      return `<div class="room ${dark ? 'dark' : ''}"><h4>${esc(room)}<span>${dark ? '🌙' : lights.length ? '☀️' : ''}</span></h4>${devs.map(dev).join('')}</div>`;
    }).join('')}</div>`;
}

// ----- settings ----------------------------------------------------------------------------------------
async function renderSettings() {
  const pane = $('#pane-settings');
  const f = fam();
  const me = f.me || f.members.find((m) => m.user_id === S.user.id) || {};
  const owner = f.owner_id === S.user.id;
  const avOpts = (sel) => S.avatarList.map((a) => `<option value="${esc(a.id)}" ${a.id === sel ? 'selected' : ''}>${esc(a.name)}${a.builtin ? '' : '（アップロード）'}</option>`).join('');
  const s = f.settings || {};
  pane.innerHTML = `<div class="settings">
    <section><h3>👤 プロフィール</h3>
      <div class="field"><span>名前</span><input id="p-name" value="${esc(S.user.name)}"></div>
      <div class="field"><span>ちびっこ（VRM）</span><select id="p-avatar">${avOpts(S.user.avatar)}</select></div>
      <div class="inline"><label class="btn">VRMをアップロード<input type="file" id="p-upload" accept=".vrm,.glb" hidden></label><span class="muted small">普段使っているアバターをそのまま使えます</span></div>
      <div class="inline"><span>誕生日</span><input id="p-bday" placeholder="MM-DD" value="${esc(S.user.birthday || '')}" style="width:90px">
        <span>声の高さ</span><input type="range" id="p-pitch" min="0.5" max="2" step="0.05" value="${esc(S.user.voice_pitch ?? 1)}"><button class="btn" id="p-test">試聴</button></div>
      <div class="inline"><input type="color" id="p-color" value="${esc(S.user.color || '#ff8a65')}"><span class="muted small">テーマカラー</span></div>
      <button class="btn primary" id="p-save">保存</button>
      <p class="muted small">${esc(S.avatars.get(S.user.avatar)?.credit || '')}</p></section>
    <section><h3>🏠 この家族での設定</h3>
      <div class="field"><span>呼び名（例: 母, 妹, 兄）</span><input id="m-nick" value="${esc(me.nickname || '')}"></div>
      <label class="inline"><input type="checkbox" id="m-private" ${me.calendar_private ? 'checked' : ''}> カレンダーを鍵付きにする（AIは中身を見ずに本人に聞きに来ます）</label>
      <div class="inline"><span>おやすみ時間</span><input type="time" id="m-qs" value="${esc(me.quiet_start || '')}"><span>〜</span><input type="time" id="m-qe" value="${esc(me.quiet_end || '')}"></div>
      <p class="muted small">おやすみ時間中は静かに届けます。「迎えに来て」などの急ぎの連絡だけは届きます。</p>
      <button class="btn primary" id="m-save">保存</button> <button class="btn danger" id="m-leave">この家族から抜ける</button></section>
    <section><h3>💌 招待リンク（LINE・メールで送れる）</h3>
      <p class="muted small">リンクを開くと招待ページが表示され、「アプリで参加」か「ブラウザでチャット」を選べます。名前・呼び名を入れると、その人専用（1回だけ使える）の招待になります。</p>
      <div class="inline"><input id="i-name" placeholder="名前（任意・例: よしこ）"><input id="i-nick" placeholder="呼び名（任意・例: 祖母）"><select id="i-avatar"><option value="">アバター自動</option>${avOpts('')}</select>
        <select id="i-exp"><option value="1">1時間</option><option value="24">24時間</option><option value="168" selected>7日間</option><option value="720">30日間</option><option value="0">期限なし</option></select>
        <button class="btn primary" id="i-go">招待リンクを作る</button></div>
      <div id="i-out"></div>
      <p class="muted small">招待コード: <b>${esc(f.code)}</b></p></section>
    <section><h3>✦ ちびAI（家族のお手伝い係） ${owner ? '' : '<span class="muted small">（オーナーのみ変更可）</span>'}</h3>
      <p class="muted small">毎朝の見守りのあいさつ、本人に代わりに聞く、見逃した連絡のまとめ、リマインド。家族と家族の間をつなぐ係です。</p>
      <label class="inline"><input type="checkbox" id="f-ai" ${f.ai_enabled ? 'checked' : ''}> ちびAIをつかう</label>
      <div class="inline"><span>名前</span><input id="f-ainame" value="${esc(f.ai_name)}" style="width:120px"><span>アバター</span><select id="f-aiav">${avOpts(f.ai_avatar)}</select></div>
      <h3 style="margin-top:18px">🧩 拡張機能（オプション）</h3>
      <p class="muted small">FamiChibiの中心は「家族とちびっこ」。ここから先は、必要な家庭だけがONにする追加機能です。</p>
      <label class="inline"><input type="checkbox" id="f-iot" ${f.iot_enabled ? 'checked' : ''}> 💡 家電操作（「おうち」タブ・ちびAIに「電気消して」）</label>
      <label class="inline"><input type="checkbox" id="f-llm" ${s.llm_enabled === true ? 'checked' : ''}> 💬 ちびAIと雑談（LLM・Ollama が必要）</label>
      <div id="ai-status" class="muted small">状態を確認中…</div>
      <div class="inline"><span>Ollama URL</span><input id="f-ollama" value="${esc(s.ollama_url || '')}" style="flex:1"><span>モデル</span><input id="f-model" value="${esc(s.model || '')}" style="width:140px"></div>
      <div class="field"><span>MCPサーバー（JSON）</span><textarea id="f-mcp">${esc(JSON.stringify(s.mcp_servers || [], null, 2))}</textarea></div>
      <p class="muted small">例: {"name":"ha","type":"http","url":"http://homeassistant.local:8086/mcp"} / {"name":"x","type":"stdio","command":"uvx","args":["ha-mcp"]}</p>
      <div class="field"><span>家族サーバー名</span><div class="row"><input id="f-emoji" class="emoji-in" value="${esc(f.emoji)}"><input id="f-name" value="${esc(f.name)}"></div></div>
      <button class="btn primary" id="f-save" ${owner ? '' : 'disabled'}>保存</button></section>
    <section><h3>🔗 アカウント</h3><p class="muted small">このブラウザのログイン情報は端末にだけ保存されます。</p><button class="btn danger" id="logout">ログアウト</button></section>
  </div>`;
  get(`/api/families/${f.id}/ai/status`).then((st) => {
    const el = $('#ai-status');
    if (!el) return;
    el.innerHTML = `エンジン: <span class="pill ${st.engine}">${st.engine === 'llm' ? `LLM（${esc(st.model)}）` : 'ルールエンジン（オフライン）'}</span>
      ${st.llm ? '' : '<span> ・ Ollamaに接続できない/モデル未導入のため、ルールで動作中</span>'}
      <div>MCPツール ${st.mcp_tools.length}個:</div><div class="tool-list">${st.mcp_tools.map((t) => `<div>🔧 ${esc(t.server)}.${esc(t.name)}</div>`).join('')}</div>
      ${Object.entries(st.mcp_errors || {}).map(([k, v]) => `<div style="color:var(--danger)">⚠ ${esc(k)}: ${esc(v)}</div>`).join('')}`;
  }).catch(() => { const el = $('#ai-status'); if (el) el.textContent = ''; });
}

async function saveSettings(id) {
  const f = fam();
  try {
    if (id === 'p-save') {
      const { user } = await patch('/api/me', { name: $('#p-name').value.trim(), avatar: $('#p-avatar').value, birthday: $('#p-bday').value.trim(),
        voice_pitch: parseFloat($('#p-pitch').value), color: $('#p-color').value });
      S.user = user;
      toast('プロフィールを保存しました');
    } else if (id === 'm-save') {
      const { family } = await patch(`/api/families/${f.id}/me`, { nickname: $('#m-nick').value.trim(), calendar_private: $('#m-private').checked,
        quiet_start: $('#m-qs').value, quiet_end: $('#m-qe').value });
      upsertFamily(family);
      toast('保存しました');
    } else if (id === 'f-save') {
      let mcp;
      try { mcp = JSON.parse($('#f-mcp').value || '[]'); } catch { toast('MCPサーバーのJSONが正しくありません', true); return; }
      const { family } = await patch(`/api/families/${f.id}`, { name: $('#f-name').value.trim(), emoji: $('#f-emoji').value, ai_enabled: $('#f-ai').checked,
        iot_enabled: $('#f-iot').checked, ai_name: $('#f-ainame').value.trim(), ai_avatar: $('#f-aiav').value,
        settings: { llm_enabled: $('#f-llm').checked, ollama_url: $('#f-ollama').value.trim(), model: $('#f-model').value.trim(), mcp_servers: mcp } });
      upsertFamily(family);
      toast('家族サーバーの設定を保存しました');
    }
    renderSidebar();
    syncCast();
    renderSettings();
  } catch (e) { fail(e); }
}

// ----- voice --------------------------------------------------------------------------------------------
function ensureVoice() {
  if (!voice) voice = new VoiceRoom({ send: (m) => socket?.send(m), myId: S.user.id, onChange: (why) => { renderVoicePane(); if (why !== 'levels') renderVoiceMini(); } });
  return voice;
}
function renderVoicePane() {
  const pane = $('#pane-voice');
  const c = channel();
  if (!c || c.kind !== 'voice' || pane.hidden) return;
  const users = S.voiceUsers[c.id] || [];
  const inRoom = voice?.cid === c.id;
  const sup = VoiceRoom.supported();
  pane.innerHTML = `<div class="voice-room"><h2>🔊 ${esc(c.name)}</h2>
    <p class="muted">${users.length ? `${users.length}人が参加中` : 'まだ誰もいません'}</p>
    <div class="vparts">${users.map((u) => { const m = member(u); return `<div class="vp ${voice?.speaking(u) ? 'speaking' : ''}">${avHtml(m || { name: '?' })}<b>${esc(label(m))}</b><small>${u === S.user.id ? (voice?.muted ? '🔇 ミュート' : 'あなた') : esc(voice?.state(u) || '')}</small></div>`; }).join('')}</div>
    ${sup ? (inRoom ? `<button class="btn" id="v-mute">${voice.muted ? '🎙 ミュート解除' : '🔇 ミュート'}</button> <button class="btn danger" id="v-leave">退出</button>`
      : '<button class="btn primary big" id="v-join" style="max-width:260px">参加する</button>')
      : '<p class="muted">ボイスチャットにはHTTPSまたはlocalhostでのアクセスが必要です（ブラウザの制限）。Androidアプリからは参加できます。</p>'}</div>`;
}
function renderVoiceMini() {
  const p = $('#voice-panel');
  if (!voice?.cid) { p.hidden = true; return; }
  const c = (fam()?.channels || []).find((x) => x.id === voice.cid);
  p.hidden = false;
  p.innerHTML = `<div class="voice-mini"><div class="row"><b>🔊 ${esc(c?.name || '通話中')}</b><span class="muted">${(S.voiceUsers[voice.cid] || []).length}人</span></div>
    <div class="row" style="margin-top:6px"><button class="btn" id="vm-mute">${voice.muted ? '🎙' : '🔇'}</button><button class="btn danger" id="vm-leave">切断</button></div></div>`;
}

// ----- chibi events -----------------------------------------------------------------------------------
function chibiFrom(uid) { return !uid || uid === 'ai' ? 'ai' : uid; }
function deliverChibi(c, ref, speak = true) {
  const id = chibiFrom(c.from);
  const m = member(id);
  living.deliver(id, { text: c.text, emotion: c.emotion, motion: c.motion, fx: c.fx, urgent: c.urgent, replies: c.replies, reasons: c.reasons, ref, duration: c.duration },
    speak ? { pitch: m?.voice_pitch || 1 } : null).then((ok) => { if (!ok) toast(`${label(m)}: ${c.text}`); });
}

function onChibiUi(ev) {
  const d = ev.detail || {};
  if (d.type === 'reply') {
    const ref = String(d.ref || '');
    if (ref.startsWith('msg:')) {
      const [, cid, mid] = ref.split(':');
      post(`/api/messages/${mid}/ack`).catch(() => {});
      sendText(d.text, cid);
    } else if (ref.startsWith('ask:')) {
      post(`/api/asks/${ref.slice(4)}/answer`, { answer: d.text }).then(() => toast('回答を届けたよ')).catch(fail);
    } else if (ref.startsWith('poke:')) {
      post(`/api/families/${S.fid}/poke`, { to: ref.slice(5), kind: d.text.includes('つんつん') ? 'poke' : 'pat' }).catch(() => {});
    } else if (ref.startsWith('memo:') || ref === 'party') {
      const first = fam()?.channels.find((c) => c.kind === 'text');
      if (first) sendText(d.text, first.id);
    }
  } else if (d.type === 'bubbleTap') {
    const ref = String(d.ref || '');
    if (ref.startsWith('msg:')) {
      const [, cid, mid] = ref.split(':');
      post(`/api/messages/${mid}/ack`).catch(() => {});
      if (cid !== S.cid) selectChannel(cid);
    }
  } else if (d.type === 'tapActor') {
    if (d.id === 'ai') {
      const ai = fam()?.channels.find((c) => c.name === 'ちびAI');
      if (ai) { selectChannel(ai.id); $('#msg-in').focus(); }
      living.react('ai', d.part === 'head' ? 'nod' : 'wave', 'sparkle');
    } else if (d.id === S.user.id) {
      living.react(d.id, 'jump', 'sparkle');
    } else {
      living.react(d.id, d.part === 'head' ? 'bothWave' : 'surprised', d.part === 'head' ? 'hearts' : 'exclaim');
      post(`/api/families/${S.fid}/poke`, { to: d.id, kind: d.part === 'head' ? 'pat' : 'poke' })
        .then(() => toast(`${label(member(d.id))}に${d.part === 'head' ? 'なでなで' : 'つんつん'}を送ったよ`)).catch(() => {});
    }
  }
}

// ----- websocket events ----------------------------------------------------------------------------------
function onEvent(ev) {
  const here = ev.family_id && ev.family_id === S.fid;
  switch (ev.type) {
    case 'hello': {
      S.user = ev.user;
      for (const f of ev.families) upsertFamily(f);
      S.families = S.families.filter((f) => ev.families.some((x) => x.id === f.id));
      S.voiceUsers = ev.voice || {};
      S.home = ev.home || S.home;
      if (S.home) setTimeout(() => living.lights(S.home?.devices?.living_light?.on !== false), 500);
      for (const a of ev.asks || []) S.asks.set(a.id, a);
      if (!$('#view-family').hidden) { renderSidebar(); syncCast(); } else if (!$('#view-home').hidden) renderHome();
      // Replay open asks as the AI chibi (e.g. after reconnect)
      setTimeout(() => {
        for (const a of ev.asks || []) if (a.family_id === S.fid) deliverChibi({ from: 'ai', text: `質問だよ：${a.question}`, emotion: 'happy', motion: 'point', fx: 'question', replies: a.options }, `ask:${a.id}`, false);
      }, 4000);
      break;
    }
    case 'message': {
      const m = ev.message;
      m.notify = ev.notify;
      const list = S.messages.get(ev.channel_id);
      if (list && !list.some((x) => x.id === m.id)) list.push(m);
      const f = S.families.find((x) => x.id === ev.family_id);
      S.typing.delete(m.user_id || 'ai');
      const viewing = here && S.cid === ev.channel_id && S.tab === 'chat' && !document.hidden;
      if (viewing) { renderMessages(); if (m.user_id !== S.user.id) markRead(); }
      else if (f && m.user_id !== S.user.id) { f.unread = f.unread || {}; f.unread[ev.channel_id] = (f.unread[ev.channel_id] || 0) + 1; if (here) renderSidebar(); else if (!$('#view-home').hidden) renderHome(); }
      if (here) {
        const a = m.analysis || {};
        const mine = ev.notify?.mode === 'self';
        let text = m.text;
        if (m.kind === 'voice') text = '🎤 ボイスメッセージが届いたよ';
        deliverChibi({ from: m.user_id, text, emotion: a.emotion, motion: a.motion, fx: a.fx, urgent: a.urgency === 'high',
          replies: mine ? [] : a.replies, reasons: (a.reasons || []).concat(ev.notify?.reason ? [ev.notify.reason] : []) },
        `msg:${ev.channel_id}:${m.id}`, !mine && ev.notify?.mode !== 'quiet' && m.kind !== 'voice');
        if (m.kind === 'voice' && !mine && m.media) {
          const au = new Audio(m.media);
          au.onplay = () => window.Chibi?.speaking(chibiFrom(m.user_id), true);
          au.onended = () => window.Chibi?.speaking(chibiFrom(m.user_id), false);
          if (living.tts) au.play().catch(() => {});
        }
      } else if (f && m.user_id !== S.user.id) {
        toast(`${f.emoji} ${label(member(m.user_id, f))}: ${m.text.slice(0, 40)}`);
      }
      break;
    }
    case 'typing':
      if (ev.user_id !== S.user?.id) { S.typing.set(ev.user_id, { cid: ev.channel_id, at: Date.now() }); renderTyping(); }
      break;
    case 'ack': {
      for (const list of S.messages.values()) {
        const m = list.find((x) => x.id === ev.message_id);
        if (m) { m.acks = [...new Set([...(m.acks || []), ev.user_id])]; if (here && S.tab === 'chat') renderMessages(); }
      }
      break;
    }
    case 'acked': toast(`👀 ${ev.text}`); break;
    case 'memo':
      if (here && S.memos) { const i = S.memos.findIndex((x) => x.id === ev.memo.id); if (i >= 0) S.memos[i] = ev.memo; else if (!ev.memo.party) S.memos.unshift(ev.memo); if (S.tab === 'memo') renderMemos(); }
      break;
    case 'memo_deleted':
      if (here && S.memos) { S.memos = S.memos.filter((x) => x.id !== ev.id); if (S.tab === 'memo') renderMemos(); }
      break;
    case 'memo_due':
      if (here) { deliverChibi(ev.chibi, `memo:${ev.memo.id}`); if (S.memos) { const mm = S.memos.find((x) => x.id === ev.memo.id); if (mm) { mm.delivered = 1; if (S.tab === 'memo') renderMemos(); } } }
      else toast(`🧊 冷蔵庫メモ: ${ev.memo.text}`);
      break;
    case 'ask':
      S.asks.set(ev.ask.id, ev.ask);
      if (here) deliverChibi({ ...ev.chibi, duration: 20000 }, `ask:${ev.ask.id}`);
      else toast(`✦ ${ev.chibi.text}`);
      break;
    case 'ask_answer':
      S.asks.delete(ev.ask.id);
      break;
    case 'nudge':
      if (here) deliverChibi({ ...ev.chibi, duration: 15000 }, `msg:${ev.message.channel_id}:${ev.message.id}`);
      else toast(`❗ ${ev.chibi.text}`, true);
      break;
    case 'poke':
      if (here) deliverChibi(ev.chibi, `poke:${ev.from}`);
      else toast(ev.chibi.text);
      break;
    case 'party':
      if (here) {
        const msgs = (ev.messages || []).map((m) => ({ from: chibiFrom(m.from), text: m.text, pitch: member(m.from)?.voice_pitch || 1 }));
        living.party(ev.title || `🎉 ${ev.reason || 'おめでとう！'}`, msgs, $('#party-banner'));
      }
      break;
    case 'presence': {
      for (const f of S.families) {
        const m = f.members.find((x) => x.user_id === ev.user_id);
        if (m) { m.online = ev.online; if (ev.presence && (!ev.family_id || ev.family_id === f.id)) m.presence = ev.presence; }
      }
      if (here) { renderSidebar(); const m = member(ev.user_id); if (m && ev.presence) living.presence([{ id: m.user_id, presence: m.presence }]); }
      break;
    }
    case 'arrive': case 'leave': {
      const m = member(ev.user_id);
      if (m && ev.presence) m.presence = ev.presence;
      if (here && m) {
        living.homeChange(m.user_id, ev.type === 'arrive', ev.presence, { name: label(m) });
        toast(ev.type === 'arrive' ? `🏠 ${label(m)}が帰ってきたよ` : `🚶 ${label(m)}がおでかけ`);
        renderSidebar();
      }
      break;
    }
    case 'family':
      upsertFamily(ev.family);
      if (here) { renderSidebar(); syncCast(); if (S.tab === 'settings' && !document.activeElement?.closest('#pane-settings')) renderSettings(); }
      break;
    case 'family_deleted':
      S.families = S.families.filter((f) => f.id !== ev.family_id);
      if (S.fid === ev.family_id) { S.fid = null; renderHome(); toast('家族サーバーから外れました'); }
      break;
    case 'voice_state':
      S.voiceUsers[ev.channel_id] = ev.users;
      voice?.onVoiceState(ev);
      if (here) { renderSidebar(); renderVoicePane(); }
      if (here && ev.joined && ev.joined !== S.user.id) toast(`🔊 ${label(member(ev.joined))}がボイスチャットに参加`);
      break;
    case 'rtc': voice?.onRtc(ev); break;
    case 'home':
      S.home = ev.home;
      living.lights(S.home?.devices?.living_light?.on !== false);
      if (here && S.tab === 'home') renderHomeTab();
      break;
    case 'event': case 'event_deleted':
      if (here && S.tab === 'cal') renderCal(true); else S.events = null;
      break;
    case 'task':
      if (here && S.tasks) { const i = S.tasks.findIndex((x) => x.id === ev.task.id); if (i >= 0) S.tasks[i] = ev.task; else S.tasks.unshift(ev.task); if (S.tab === 'task') renderTasks(); }
      break;
    case 'task_deleted':
      if (here && S.tasks) { S.tasks = S.tasks.filter((x) => x.id !== ev.id); if (S.tab === 'task') renderTasks(); }
      break;
    default: break;
  }
}

// ----- demo director ----------------------------------------------------------------------------------------
async function openDemo() {
  const d = $('#demo-drawer');
  d.hidden = !d.hidden;
  if (d.hidden) return;
  if (!S.scenarios) {
    try { S.scenarios = (await get('/api/demo/scenarios')).scenarios; } catch (e) { fail(e); return; }
  }
  $('#demo-buttons').innerHTML = Object.entries(S.scenarios).map(([k, v]) => `<button class="btn" data-sc="${esc(k)}">${esc(v)}</button>`).join('');
}

// ----- side (mobile) ------------------------------------------------------------------------------------------
function openSide() { $('#sidebar').classList.add('open'); $('#scrim').classList.add('show'); }
function closeSide() { $('#sidebar').classList.remove('open'); $('#scrim').classList.remove('show'); }

// ---------------------------------------------------------------------------------------------
// wiring
// ---------------------------------------------------------------------------------------------
function wire() {
  $('#theme-toggle').onclick = toggleTheme;
  $('#theme-toggle2').onclick = toggleTheme;
  $('#cf-go').onclick = createFamily;
  $('#jf-go').onclick = joinFamily;
  $('#demo-seed').onclick = seedDemo;
  $('#back-home').onclick = () => { S.fid = null; try { localStorage.removeItem('famichibi.fid'); } catch { /* ignore */ } living.stopSpeech(); renderHome(); };
  $('#open-side').onclick = openSide;
  $('#scrim').onclick = closeSide;
  $('#tabs').onclick = (e) => { const b = e.target.closest('button'); if (b) setTab(b.dataset.tab); };
  $('#tts-toggle').onclick = () => {
    living.tts = !living.tts;
    $('#tts-toggle').textContent = living.tts ? '🔊' : '🔈';
    if (!living.tts) living.stopSpeech();
    toast(living.tts ? '読み上げON' : '読み上げOFF');
  };
  $('#stage-toggle').onclick = () => $('#living').classList.toggle('collapsed');
  $('#channel-list').onclick = (e) => { const c = e.target.closest('.chan'); if (c) selectChannel(c.dataset.id); };
  $('#member-list').onclick = (e) => {
    const b = e.target.closest('.poke');
    if (b) post(`/api/families/${S.fid}/poke`, { to: b.dataset.to, kind: 'poke' }).then(() => toast('つんつんを送ったよ 👉')).catch(fail);
  };
  $('#add-channel').onclick = async () => {
    const fd = await dialog('チャンネルを追加', `<input name="name" placeholder="名前（例: 買い物）" required><select name="kind"><option value="text">テキスト</option><option value="voice">ボイス</option></select>`, '追加');
    if (!fd) return;
    try { await post(`/api/families/${S.fid}/channels`, { name: fd.get('name'), kind: fd.get('kind') }); } catch (e) { fail(e); }
  };
  $('#composer').onsubmit = (e) => { e.preventDefault(); const t = $('#msg-in').value; $('#msg-in').value = ''; sendText(t); };
  $('#msg-in').oninput = onTyping;
  $('#rec-btn').onclick = toggleRecord;
  $('#quick').onclick = (e) => { const b = e.target.closest('button'); if (b) sendText(b.textContent); };
  $('#messages').onclick = (e) => {
    const b = e.target.closest('.ack-btn');
    if (b) post(`/api/messages/${b.dataset.id}/ack`).catch(fail);
  };
  // memo
  $('#pane-memo').addEventListener('input', (e) => { if (e.target.id === 'memo-text') memoPreview(); });
  $('#pane-memo').addEventListener('submit', async (e) => {
    e.preventDefault();
    const t = $('#memo-text').value.trim();
    if (!t) return;
    try {
      await post(`/api/families/${S.fid}/memos`, { text: t, target_id: $('#memo-target').value || null, color: S.memoColor });
      $('#memo-text').value = '';
      memoPreview();
    } catch (err) { fail(err); }
  });
  $('#pane-memo').addEventListener('click', async (e) => {
    const dot = e.target.closest('.color-dot');
    if (dot) { S.memoColor = dot.dataset.c; renderMemos(); return; }
    const b = e.target.closest('[data-act]');
    if (!b) return;
    const id = b.closest('.memo').dataset.id;
    const m = S.memos.find((x) => x.id === id);
    try {
      if (b.dataset.act === 'done') await patch(`/api/memos/${id}`, { done: !m.done });
      else await del(`/api/memos/${id}`);
    } catch (err) { fail(err); }
  });
  // calendar
  $('#pane-cal').addEventListener('click', (e) => {
    const nav = e.target.closest('[data-nav]');
    if (nav) { S.calMonth = new Date(S.calMonth.getFullYear(), S.calMonth.getMonth() + Number(nav.dataset.nav), 1); renderCal(true); return; }
    if (e.target.closest('#ev-add')) { addEvent(S.calDay); return; }
    const dl = e.target.closest('[data-del-ev]');
    if (dl) { del(`/api/events/${dl.dataset.delEv}`).then(() => renderCal(true)).catch(fail); return; }
    const cell = e.target.closest('.cal-cell');
    if (cell) { S.calDay = Number(cell.dataset.d); renderCal(); }
  });
  // tasks
  $('#pane-task').addEventListener('submit', async (e) => {
    e.preventDefault();
    const t = $('#task-title').value.trim();
    if (!t) return;
    try { await post(`/api/families/${S.fid}/tasks`, { title: t, assignee_id: $('#task-assignee').value || null }); $('#task-title').value = ''; } catch (err) { fail(err); }
  });
  $('#pane-task').addEventListener('change', async (e) => {
    const row = e.target.closest('.task');
    if (!row) return;
    const id = row.dataset.id;
    try {
      if (e.target.dataset.act === 'done') await patch(`/api/tasks/${id}`, { done: e.target.checked });
      if (e.target.dataset.act === 'assign') await patch(`/api/tasks/${id}`, { assignee_id: e.target.value || null });
    } catch (err) { fail(err); }
  });
  $('#pane-task').addEventListener('click', (e) => {
    const b = e.target.closest('[data-act="del"]');
    if (b) del(`/api/tasks/${b.closest('.task').dataset.id}`).catch(fail);
  });
  // home
  $('#pane-home').addEventListener('change', async (e) => {
    const i = e.target.closest('[data-dev]');
    if (!i) return;
    try { S.home = await post(`/api/families/${S.fid}/home/${i.dataset.dev}`, { [i.dataset.key]: i.checked }); renderHomeTab(); } catch (err) { fail(err); renderHomeTab(); }
  });
  // settings
  $('#pane-settings').addEventListener('click', async (e) => {
    const id = e.target.id;
    if (['p-save', 'm-save', 'f-save'].includes(id)) saveSettings(id);
    else if (id === 'i-copy') { navigator.clipboard?.writeText(e.target.dataset.url).then(() => toast('コピーしました'), () => prompt('コピーしてください', e.target.dataset.url)); }
    else if (id === 'p-test') living.speak(S.user.id, 'こんにちは！この声で家族に届けるよ', parseFloat($('#p-pitch').value));
    else if (id === 'logout') { if (confirm('ログアウトしますか？（このブラウザのアカウント情報が消えます）')) { auth.token = ''; location.reload(); } }
    else if (id === 'm-leave') {
      if (!confirm('この家族サーバーから抜けますか？')) return;
      try { await del(`/api/families/${S.fid}/members/${S.user.id}`); } catch (err) { fail(err); }
    } else if (id === 'i-go') {
      try {
        const r = await post(`/api/families/${S.fid}/invites`, { preset_name: $('#i-name').value.trim(), preset_nickname: $('#i-nick').value.trim(), preset_avatar: $('#i-avatar').value, expires_hours: parseFloat($('#i-exp').value) });
        const until = r.expires_at ? `${new Date(r.expires_at * 1000).toLocaleString('ja-JP', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })} まで有効` : '期限なし';
        $('#i-out').innerHTML = `<div class="qr-box"><img src="${esc(r.qr)}" alt="QR"><div><div><b>招待リンク</b>（${until}${r.single_use ? '・1回だけ' : ''}）</div>
          <code><a href="${esc(r.url)}" target="_blank">${esc(r.url)}</a></code>
          <div style="margin-top:8px"><button class="btn" id="i-copy" data-url="${esc(r.url)}">リンクをコピー</button></div>
          <div class="muted small" style="margin-top:6px">NFCカード用（アプリ直接）: <code>${esc(r.link)}</code></div></div></div>`;
      } catch (err) { fail(err); }
    }
  });
  $('#pane-settings').addEventListener('change', async (e) => {
    if (e.target.id !== 'p-upload' || !e.target.files[0]) return;
    const fd = new FormData();
    fd.append('file', e.target.files[0]);
    fd.append('name', e.target.files[0].name.replace(/\.(vrm|glb)$/i, ''));
    toast('アップロード中…');
    try {
      const { avatar } = await api('/api/avatars', { method: 'POST', form: fd });
      await loadAvatars();
      const { user } = await patch('/api/me', { avatar: avatar.id });
      S.user = user;
      toast('アバターを変更しました ✨');
      renderSettings();
    } catch (err) { fail(err); }
  });
  // voice
  $('#pane-voice').addEventListener('click', async (e) => {
    if (e.target.id === 'v-join') { try { await ensureVoice().join(S.cid); } catch (err) { toast('マイクを使えませんでした', true); console.warn(err); } }
    if (e.target.id === 'v-leave') voice?.leave();
    if (e.target.id === 'v-mute') voice?.toggleMute();
  });
  $('#voice-panel').addEventListener('click', (e) => {
    if (e.target.id === 'vm-leave') voice?.leave();
    if (e.target.id === 'vm-mute') voice?.toggleMute();
  });
  // demo
  $('#demo-fab').onclick = openDemo;
  $('#demo-close').onclick = () => { $('#demo-drawer').hidden = true; };
  $('#demo-buttons').onclick = async (e) => {
    const b = e.target.closest('[data-sc]');
    if (!b) return;
    try { const r = await post(`/api/families/${S.fid}/demo/${b.dataset.sc}`); toast(`▶ ${r.scenario}`); } catch (err) { fail(err); }
  };
  $('#demo-say').onsubmit = async (e) => {
    e.preventDefault();
    const fd = new FormData();
    fd.append('nickname', $('#demo-nick').value);
    fd.append('text', $('#demo-text').value);
    if (!$('#demo-text').value.trim()) return;
    try { await api(`/api/families/${S.fid}/demo-say`, { method: 'POST', form: fd }); $('#demo-text').value = ''; } catch (err) { fail(err); }
  };
  window.addEventListener('chibi', onChibiUi);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) markRead(); });
  window.addEventListener('beforeunload', () => voice?.leave());
}

// ---------------------------------------------------------------------------------------------
// boot
// ---------------------------------------------------------------------------------------------
async function boot(openFid) {
  const params = new URLSearchParams(location.search);
  const invite = params.get('invite');
  if (!auth.token) {
    let inv = null;
    if (invite) { try { inv = { ...(await get(`/api/invites/${invite}`)), token: invite }; } catch (e) { fail(e); } }
    await showOnboard(inv);
    return;
  }
  try {
    const me = await get('/api/me');
    S.user = me.user;
    S.families = me.families;
  } catch (e) {
    if (e.status === 401) { auth.token = ''; return boot(openFid); }
    fail(e);
    return;
  }
  await loadAvatars().catch(() => {});
  if (invite) {
    try {
      const r = await post(`/api/invites/${invite}/claim`, {});
      upsertFamily(r.family);
      openFid = r.family.id;
    } catch (e) { fail(e); }
    history.replaceState(null, '', '/');
  }
  if (!socket) {
    socket = new Socket(onEvent, (st) => { $('#stage-status').textContent = st === 'open' ? '' : '（再接続中…）'; });
    socket.connect();
  }
  let last = openFid;
  if (!last) { try { last = localStorage.getItem('famichibi.fid'); } catch { /* ignore */ } }
  if (last && S.families.some((f) => f.id === last)) openFamily(last);
  else renderHome();
}

let theme = 'light';
try { theme = localStorage.getItem('famichibi.theme') || (matchMedia('(prefers-color-scheme: dark)').matches ? 'dark' : 'light'); } catch { /* ignore */ }
applyTheme(theme);
wire();
living.init();
boot();

// for debugging / tests
window.__famichibi = { S, living };
