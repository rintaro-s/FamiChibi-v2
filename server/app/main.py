"""FamiChibi host server.

  python -m app.main  [--host 0.0.0.0] [--port 8000]

REST under /api, WebSocket at /ws?token=..., web client at /, chibi stage at /stage/, avatars at /avatars/.
"""
from __future__ import annotations

import argparse
import asyncio
import io
import json
import logging
import os
import re
import secrets
import shutil
import socket
import time
import unicodedata
import urllib.parse
from contextlib import asynccontextmanager
from pathlib import Path
from typing import Any

import qrcode
from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, Query, Request, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse, HTMLResponse, JSONResponse, RedirectResponse, Response
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel

from . import analyzer, timeparse
from .agent import Agent
from .db import DB, new_id, now
from .home import STATE as HOME
from .hub import Hub
from .services import AI_ID, Services, next_birthday_ts

VERSION = "1.0.0"
BASE = Path(__file__).resolve().parent
ROOT = BASE.parent
DATA = Path(os.environ.get("FAMICHIBI_DATA", ROOT / "data"))
MEDIA = DATA / "media"
UPLOADED_AVATARS = DATA / "avatars"
BUILTIN_AVATARS = Path(os.environ.get("FAMICHIBI_AVATARS", ROOT.parent / "assets" / "avatars"))
STATIC = BASE / "static"
DISCOVERY_PORT = 47810

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
log = logging.getLogger("famichibi")

db = DB(DATA / "famichibi.db")
hub = Hub()
svc = Services(db, hub)
agent = Agent(svc)
svc.agent = agent
HOME.path = DATA / "home.json"

BUILTIN_META = {  # stem: (name, color, credit)
    "AvatarSample_C": ("ユウ", "#4f7cff", "AvatarSample_C © pixiv Inc. VRoid Project"),
    "AvatarSample_K": ("ミナ", "#2ec4b6", "AvatarSample_K © pixiv Inc. VRoid Project"),
    "AvatarSample_L": ("ハル", "#b57bff", "AvatarSample_L © pixiv Inc. VRoid Project"),
    "AvatarSample_M": ("ソラ", "#ff8a65", "AvatarSample_M © pixiv Inc. VRoid Project"),
    "VRM1_Sample": ("サクラ", "#ff7a90", "VRM1 Sample © pixiv Inc.（VRM Public License 1.0）"),
    "AliciaSolid": ("アリシア", "#5ec8ff", "ニコニ立体ちゃん（アリシア・ソリッド）© DWANGO Co., Ltd.（https://3d.nicovideo.jp/alicia/rule.html）"),
}


def seed_avatars() -> None:
    if not BUILTIN_AVATARS.exists():
        return
    for f in sorted(BUILTIN_AVATARS.glob("*.vrm")):
        aid = "builtin_" + f.stem
        if not db.one("SELECT id FROM avatars WHERE id=?", (aid,)):
            name, _, credit = BUILTIN_META.get(f.stem, (f.stem, "", f.stem))
            db.insert("avatars", {"id": aid, "name": name, "file": f.name, "owner_id": None, "builtin": 1,
                                  "credit": credit, "size": f.stat().st_size, "created_at": now()})


def home_listener(snapshot: dict) -> None:
    loop = MAIN_LOOP.get("loop")
    if not loop:
        return
    async def push():
        for fam in db.all("SELECT id FROM families WHERE iot_enabled=1"):
            await svc.broadcast_family(fam["id"], {"type": "home", "home": snapshot})
    asyncio.run_coroutine_threadsafe(push(), loop)


MAIN_LOOP: dict[str, Any] = {}


# ---------------------------------------------------------------------------------------------
# lifespan: scheduler + LAN discovery
# ---------------------------------------------------------------------------------------------
class DiscoveryProtocol(asyncio.DatagramProtocol):
    def connection_made(self, transport):
        self.transport = transport

    def datagram_received(self, data, addr):
        if not data.startswith(b"FAMICHIBI_DISCOVER"):
            return
        ip = local_ip_for(addr[0])
        port = MAIN_LOOP.get("port", 8000)
        reply = {"app": "famichibi", "name": db.kv_get("host_name", socket.gethostname()), "url": f"http://{ip}:{port}", "version": VERSION}
        self.transport.sendto(json.dumps(reply, ensure_ascii=False).encode(), addr)


def lan_ips() -> list[str]:
    """Private IPv4 addresses of this host: phones that can reach one of them are "at home"."""
    import ipaddress
    ips = {local_ip_for()}
    try:
        for info in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            ips.add(info[4][0])
    except OSError:
        pass
    out = []
    for ip in ips:
        try:
            a = ipaddress.ip_address(ip)
            if a.is_private and not a.is_loopback and not ip.startswith(("172.17.", "172.18.", "172.19.")):
                out.append(ip)
        except ValueError:
            pass
    return sorted(out)


def local_ip_for(peer: str = "8.8.8.8") -> str:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect((peer if peer and not peer.startswith("127.") else "8.8.8.8", 9))
        return s.getsockname()[0]
    except OSError:
        return "127.0.0.1"
    finally:
        s.close()


async def scheduler() -> None:
    n = 0
    while True:
        try:
            await svc.tick()
            if n % 6 == 0:
                await svc.birthday_check()
            if n % 12 == 3:
                await svc.presence.tick()
        except Exception:
            log.exception("scheduler")
        n += 1
        await asyncio.sleep(5)


@asynccontextmanager
async def lifespan(app: FastAPI):
    loop = asyncio.get_running_loop()
    MAIN_LOOP["loop"] = loop
    seed_avatars()
    HOME.listeners.append(home_listener)
    tasks = [loop.create_task(scheduler())]
    try:
        transport, _ = await loop.create_datagram_endpoint(DiscoveryProtocol, local_addr=("0.0.0.0", DISCOVERY_PORT), allow_broadcast=True)
    except OSError as e:
        log.warning("LAN discovery disabled: %s", e)
        transport = None
    yield
    for t in tasks:
        t.cancel()
    if transport:
        transport.close()


app = FastAPI(title="FamiChibi Host", version=VERSION, lifespan=lifespan)
app.add_middleware(CORSMiddleware, allow_origins=["*"], allow_methods=["*"], allow_headers=["*"])


# ---------------------------------------------------------------------------------------------
# auth helpers
# ---------------------------------------------------------------------------------------------
def current_user(authorization: str = Header(default=""), token: str = Query(default="")) -> dict:
    tok = token or (authorization[7:] if authorization.lower().startswith("bearer ") else "")
    u = db.user_by_token(tok) if tok else None
    if not u:
        raise HTTPException(401, "ログインが必要です")
    return u


def require_member(fid: str, user: dict) -> tuple[dict, dict]:
    fam = db.family(fid)
    if not fam:
        raise HTTPException(404, "家族サーバーが見つかりません")
    m = db.member(fid, user["id"])
    if not m:
        raise HTTPException(403, "この家族サーバーのメンバーではありません")
    return fam, m


def require_owner(fid: str, user: dict) -> dict:
    fam, m = require_member(fid, user)
    if m["role"] != "owner":
        raise HTTPException(403, "オーナーのみ変更できます")
    return fam


def new_code() -> str:
    while True:
        c = "".join(secrets.choice("ABCDEFGHJKLMNPQRSTUVWXYZ23456789") for _ in range(6))
        if not db.one("SELECT id FROM families WHERE code=?", (c,)):
            return c


def public_base(request: Request) -> str:
    host = request.headers.get("host", "")
    if host.startswith(("localhost", "127.0.0.1")) or not host:
        return f"http://{local_ip_for()}:{MAIN_LOOP.get('port', 8000)}"
    return f"{request.url.scheme}://{host}"


# ---------------------------------------------------------------------------------------------
# models
# ---------------------------------------------------------------------------------------------
class RegisterIn(BaseModel):
    name: str
    avatar: str = ""
    color: str = ""
    birthday: str = ""


class MeIn(BaseModel):
    name: str | None = None
    avatar: str | None = None
    color: str | None = None
    birthday: str | None = None
    voice_pitch: float | None = None


class FamilyIn(BaseModel):
    name: str
    emoji: str = "🏠"
    nickname: str = ""


class FamilyPatch(BaseModel):
    name: str | None = None
    emoji: str | None = None
    ai_enabled: bool | None = None
    iot_enabled: bool | None = None
    ai_name: str | None = None
    ai_avatar: str | None = None
    settings: dict | None = None


class MemberPatch(BaseModel):
    nickname: str | None = None
    calendar_private: bool | None = None
    share_presence: bool | None = None
    watch: bool | None = None
    quiet_start: str | None = None
    quiet_end: str | None = None


class JoinIn(BaseModel):
    code: str
    nickname: str = ""


class ChannelIn(BaseModel):
    name: str
    kind: str = "text"


class MessageIn(BaseModel):
    text: str
    kind: str = "text"
    reply_to: int | None = None
    meta: dict | None = None


class MemoIn(BaseModel):
    text: str
    notify_at: float | None = None
    target_id: str | None = None
    color: str = "#fff3a3"
    auto_time: bool = True
    party: bool = False


class MemoPatch(BaseModel):
    text: str | None = None
    notify_at: float | None = None
    done: bool | None = None
    color: str | None = None


class EventIn(BaseModel):
    title: str
    start: float
    end: float | None = None
    all_day: bool = False
    private: bool = False
    shared: bool = False
    note: str = ""


class TaskIn(BaseModel):
    title: str
    assignee_id: str | None = None
    due: float | None = None


class TaskPatch(BaseModel):
    title: str | None = None
    assignee_id: str | None = None
    done: bool | None = None


class AnswerIn(BaseModel):
    answer: str


class AskAIIn(BaseModel):
    text: str
    channel_id: str | None = None


class InviteIn(BaseModel):
    preset_name: str = ""
    preset_nickname: str = ""
    preset_avatar: str = ""


class ClaimIn(BaseModel):
    name: str = ""


class PokeIn(BaseModel):
    to: str
    kind: str = "poke"  # poke | pat | wave | cheer


class DeviceIn(BaseModel):
    on: bool | None = None
    open: bool | None = None
    locked: bool | None = None


# ---------------------------------------------------------------------------------------------
# info / users
# ---------------------------------------------------------------------------------------------
@app.get("/api/info")
def info(request: Request):
    port = MAIN_LOOP.get("port", 8000)
    return {"app": "famichibi", "name": db.kv_get("host_name", socket.gethostname()), "version": VERSION,
            "url": public_base(request), "lan_urls": [f"http://{ip}:{port}" for ip in lan_ips()],
            "public_urls": [u.strip() for u in os.environ.get("FAMICHIBI_PUBLIC_URLS", "").split(",") if u.strip()], "families": db.one("SELECT COUNT(*) AS n FROM families")["n"], "time": time.time()}


def create_user(name: str, avatar: str = "", color: str = "", birthday: str = "", virtual: bool = False) -> dict:
    uid = new_id("u_")
    token = ("virtual:" if virtual else "") + secrets.token_urlsafe(24)
    if not avatar:
        avs = db.all("SELECT id FROM avatars WHERE builtin=1 AND id != 'builtin_AliciaSolid' ORDER BY id")
        avatar = avs[db.one("SELECT COUNT(*) AS n FROM users")["n"] % len(avs)]["id"] if avs else ""
    if not color:
        stem = avatar.replace("builtin_", "")
        color = BUILTIN_META.get(stem, ("", "#ff8a65", ""))[1] or "#ff8a65"
    u = {"id": uid, "name": name.strip()[:24] or "ななし", "token": token, "avatar": avatar, "color": color, "birthday": birthday,
         "voice_pitch": 1.0, "created_at": now()}
    db.insert("users", u)
    return u


def unused_avatar(fid: str) -> str:
    """A builtin avatar nobody in this family uses yet, so every chibi looks different."""
    used = {m["avatar"] for m in db.members(fid)} | {db.family(fid)["ai_avatar"]}
    avs = [a["id"] for a in db.all("SELECT id FROM avatars WHERE builtin=1 ORDER BY id")]
    return next((a for a in avs if a not in used), "")


@app.post("/api/register")
def register(body: RegisterIn):
    u = create_user(body.name, body.avatar, body.color, body.birthday)
    return {"user": svc.user_public(u), "token": u["token"]}


@app.get("/api/me")
def me(user: dict = Depends(current_user)):
    return {"user": svc.user_public(user), "families": [svc.family_out(f, user["id"]) for f in db.families_of(user["id"])]}


@app.patch("/api/me")
async def patch_me(body: MeIn, user: dict = Depends(current_user)):
    data = {k: v for k, v in body.model_dump().items() if v is not None}
    if "birthday" in data and data["birthday"] and not re.fullmatch(r"\d{2}-\d{2}", data["birthday"]):
        raise HTTPException(400, "誕生日は MM-DD 形式で指定してください")
    db.update("users", {"id": user["id"]}, data)
    u = db.user(user["id"])
    for f in db.families_of(user["id"]):
        await svc.broadcast_family(f["id"], {"type": "family", "family": svc.family_out(f)})
    return {"user": svc.user_public(u)}


# ---------------------------------------------------------------------------------------------
# avatars
# ---------------------------------------------------------------------------------------------
def avatar_out(a: dict) -> dict:
    return {"id": a["id"], "name": a["name"], "url": f"/avatars/{a['file']}", "builtin": bool(a["builtin"]), "credit": a["credit"],
            "size": a["size"], "owner_id": a["owner_id"]}


@app.get("/api/avatars")
def list_avatars():
    return {"avatars": [avatar_out(a) for a in db.all("SELECT * FROM avatars ORDER BY builtin DESC, created_at")]}


@app.post("/api/avatars")
async def upload_avatar(file: UploadFile = File(...), name: str = Form(""), user: dict = Depends(current_user)):
    head = await file.read(4)
    if head != b"glTF":
        raise HTTPException(400, "VRM(glb)ファイルではありません")
    UPLOADED_AVATARS.mkdir(parents=True, exist_ok=True)
    aid = new_id("av_")
    path = UPLOADED_AVATARS / f"{aid}.vrm"
    size = 4
    with path.open("wb") as f:
        f.write(head)
        while chunk := await file.read(1 << 20):
            size += len(chunk)
            if size > 80 << 20:
                f.close()
                path.unlink(missing_ok=True)
                raise HTTPException(413, "ファイルが大きすぎます（80MBまで）")
            f.write(chunk)
    credit = ""
    try:
        credit = vrm_credit(path)
    except Exception:
        pass
    a = {"id": aid, "name": name or Path(file.filename or "avatar").stem, "file": path.name, "owner_id": user["id"], "builtin": 0,
         "credit": credit, "size": size, "created_at": now()}
    db.insert("avatars", a)
    return {"avatar": avatar_out(a)}


def vrm_credit(path: Path) -> str:
    import struct
    with path.open("rb") as f:
        f.read(12)
        n = struct.unpack("<I", f.read(4))[0]
        f.read(4)
        js = json.loads(f.read(n))
    ext = js.get("extensions", {})
    meta = (ext.get("VRMC_vrm") or {}).get("meta") or (ext.get("VRM") or {}).get("meta") or {}
    authors = meta.get("authors") or [meta.get("author", "")]
    return f"{meta.get('name') or meta.get('title') or ''} by {', '.join(a for a in authors if a)}".strip()


@app.get("/avatars/{name}")
def get_avatar(name: str):
    if "/" in name or ".." in name:
        raise HTTPException(404)
    for d in (UPLOADED_AVATARS, BUILTIN_AVATARS):
        p = d / name
        if p.exists():
            return FileResponse(p, media_type="model/gltf-binary", headers={"Cache-Control": "public, max-age=86400"})
    raise HTTPException(404)


# ---------------------------------------------------------------------------------------------
# families
# ---------------------------------------------------------------------------------------------
def make_family(owner: dict, name: str, emoji: str = "🏠", nickname: str = "") -> dict:
    fid = new_id("f_")
    avs = db.all("SELECT id FROM avatars WHERE builtin=1 ORDER BY id")
    fam = {"id": fid, "name": name.strip()[:30] or "わが家", "emoji": emoji or "🏠", "code": new_code(), "owner_id": owner["id"],
           "ai_enabled": 1, "iot_enabled": 1, "ai_name": "ちびAI", "ai_avatar": "builtin_AliciaSolid" if any(a["id"] == "builtin_AliciaSolid" for a in avs) else (avs[0]["id"] if avs else ""),
           "settings": {"ollama_url": "http://127.0.0.1:11434", "model": "qwen3.5:9b", "llm_enabled": True,
                        "mcp_servers": [{"name": "home", "type": "builtin"}]}, "created_at": now()}
    db.insert("families", fam)
    db.insert("members", {"family_id": fid, "user_id": owner["id"], "role": "owner", "nickname": nickname, "joined_at": now()})
    for i, (cname, kind) in enumerate([("リビング", "text"), ("ちびAI", "text"), ("おしゃべり部屋", "voice")]):
        db.insert("channels", {"id": new_id("c_"), "family_id": fid, "name": cname, "kind": kind, "position": i, "created_at": now()})
    return db.family(fid)


@app.get("/api/families")
def my_families(user: dict = Depends(current_user)):
    return {"families": [svc.family_out(f, user["id"]) for f in db.families_of(user["id"])]}


@app.post("/api/families")
def create_family(body: FamilyIn, user: dict = Depends(current_user)):
    fam = make_family(user, body.name, body.emoji, body.nickname)
    return {"family": svc.family_out(fam, user["id"])}


async def join_family(fam: dict, user: dict, nickname: str = "") -> None:
    if db.member(fam["id"], user["id"]):
        return
    db.insert("members", {"family_id": fam["id"], "user_id": user["id"], "role": "member", "nickname": nickname, "joined_at": now()})
    await svc.broadcast_family(fam["id"], {"type": "family", "family": svc.family_out(fam)})
    await svc.ai_say(fam["id"], f"{nickname or user['name']}が家族サーバーに参加したよ！よろしくね👋", emotion="excited", motion="bothWave",
                     reasons=["新しいメンバーの参加"])


@app.post("/api/families/join")
async def join_by_code(body: JoinIn, user: dict = Depends(current_user)):
    fam = db.one("SELECT * FROM families WHERE code=?", (unicodedata.normalize("NFKC", body.code).strip().upper(),))
    if not fam:
        raise HTTPException(404, "招待コードが見つかりません")
    await join_family(fam, user, body.nickname)
    return {"family": svc.family_out(fam, user["id"])}


@app.get("/api/families/{fid}")
def get_family(fid: str, user: dict = Depends(current_user)):
    fam, _ = require_member(fid, user)
    return {"family": svc.family_out(fam, user["id"])}


@app.patch("/api/families/{fid}")
async def patch_family(fid: str, body: FamilyPatch, user: dict = Depends(current_user)):
    fam = require_owner(fid, user)
    data = {k: v for k, v in body.model_dump().items() if v is not None}
    for b in ("ai_enabled", "iot_enabled"):
        if b in data:
            data[b] = int(data[b])
    if "settings" in data:
        merged = dict(fam["settings"] or {})
        merged.update(data["settings"])
        data["settings"] = merged
    db.update("families", {"id": fid}, data)
    fam = db.family(fid)
    await svc.broadcast_family(fid, {"type": "family", "family": svc.family_out(fam)})
    return {"family": svc.family_out(fam, user["id"])}


@app.delete("/api/families/{fid}")
async def delete_family(fid: str, user: dict = Depends(current_user)):
    require_owner(fid, user)
    uids = svc.family_member_ids(fid)
    db.exec("DELETE FROM families WHERE id=?", (fid,))
    await hub.send_users(uids, {"type": "family_deleted", "family_id": fid})
    return {"ok": True}


@app.patch("/api/families/{fid}/me")
async def patch_membership(fid: str, body: MemberPatch, user: dict = Depends(current_user)):
    fam, _ = require_member(fid, user)
    data = {k: v for k, v in body.model_dump().items() if v is not None}
    for b in ("calendar_private", "share_presence", "watch"):
        if b in data:
            data[b] = int(data[b])
    db.update("members", {"family_id": fid, "user_id": user["id"]}, data)
    await svc.broadcast_family(fid, {"type": "family", "family": svc.family_out(fam)})
    return {"family": svc.family_out(fam, user["id"])}


class WatchIn(BaseModel):
    watch: bool


@app.patch("/api/families/{fid}/members/{uid}/watch")
async def set_watch(fid: str, uid: str, body: WatchIn, user: dict = Depends(current_user)):
    """Turn gentle watching over (見守り) on/off for a member (e.g. grandma). Visible to that member too."""
    fam, _ = require_member(fid, user)
    if not db.member(fid, uid):
        raise HTTPException(404)
    db.update("members", {"family_id": fid, "user_id": uid}, {"watch": int(body.watch)})
    await svc.broadcast_family(fid, {"type": "family", "family": svc.family_out(fam)})
    if body.watch:
        who = svc.display_name(fid, user["id"])
        await hub.send_user(uid, {"type": "poke", "family_id": fid, "from": "ai", "kind": "care",
                                  "chibi": {"from": "ai", "text": f"{who}が見守りをONにしたよ。毎朝あいさつに来るね☀", "emotion": "happy",
                                            "motion": "wave", "fx": "hearts", "replies": ["よろしくね"],
                                            "reasons": ["見守りは本人にも知らされます（こっそり監視はしません）"]}})
    return {"family": svc.family_out(fam, user["id"])}


@app.get("/api/families/{fid}/timeline")
def timeline(fid: str, user: dict = Depends(current_user)):
    require_member(fid, user)
    return {"timeline": db.kv_get(f"timeline:{fid}", [])[-40:][::-1]}


@app.delete("/api/families/{fid}/members/{uid}")
async def remove_member(fid: str, uid: str, user: dict = Depends(current_user)):
    fam, m = require_member(fid, user)
    if uid != user["id"] and m["role"] != "owner":
        raise HTTPException(403)
    db.exec("DELETE FROM members WHERE family_id=? AND user_id=?", (fid, uid))
    await svc.broadcast_family(fid, {"type": "family", "family": svc.family_out(fam)})
    await hub.send_user(uid, {"type": "family_deleted", "family_id": fid})
    return {"ok": True}


# ----- invites (QR / NFC card with a preset profile) --------------------------------------------
@app.post("/api/families/{fid}/invites")
def create_invite(fid: str, body: InviteIn, request: Request, user: dict = Depends(current_user)):
    fam, _ = require_member(fid, user)
    tok = secrets.token_urlsafe(10)
    db.insert("invites", {"token": tok, "family_id": fid, "preset_name": body.preset_name, "preset_nickname": body.preset_nickname,
                          "preset_avatar": body.preset_avatar, "created_by": user["id"], "created_at": now()})
    base = public_base(request)
    link = f"famichibi://join?host={base}&invite={tok}"
    return {"token": tok, "link": link, "web": f"{base}/join/{tok}", "qr": f"/api/qr?data={urllib.parse.quote(link, safe='')}", "code": fam["code"],
            "code_link": f"famichibi://join?host={base}&code={fam['code']}"}


@app.get("/api/invites/{tok}")
def invite_info(tok: str):
    inv = db.one("SELECT * FROM invites WHERE token=?", (tok,))
    if not inv or inv["used_by"]:
        raise HTTPException(404, "この招待は使えません")
    fam = db.family(inv["family_id"])
    return {"family": {"id": fam["id"], "name": fam["name"], "emoji": fam["emoji"]}, "preset_name": inv["preset_name"],
            "preset_nickname": inv["preset_nickname"], "preset_avatar": inv["preset_avatar"]}


@app.post("/api/invites/{tok}/claim")
async def claim_invite(tok: str, body: ClaimIn, authorization: str = Header(default="")):
    """Join with one tap. If the caller has no account yet (e.g. grandma tapping an NFC card), one is created."""
    inv = db.one("SELECT * FROM invites WHERE token=?", (tok,))
    if not inv or inv["used_by"]:
        raise HTTPException(404, "この招待は使えません")
    user = db.user_by_token(authorization[7:]) if authorization.lower().startswith("bearer ") else None
    if not user:
        user = create_user(body.name or inv["preset_name"] or "家族", inv["preset_avatar"] or unused_avatar(inv["family_id"]))
    fam = db.family(inv["family_id"])
    await join_family(fam, user, inv["preset_nickname"])
    if inv["preset_name"] or inv["preset_nickname"]:
        db.update("invites", {"token": tok}, {"used_by": user["id"]})
    return {"user": svc.user_public(user), "token": user["token"], "family": svc.family_out(fam, user["id"])}


@app.get("/api/qr")
def qr_png(data: str):
    img = qrcode.make(data, box_size=8, border=2)
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return Response(buf.getvalue(), media_type="image/png")


# ---------------------------------------------------------------------------------------------
# channels & messages
# ---------------------------------------------------------------------------------------------
def require_channel(cid: str, user: dict) -> tuple[dict, dict]:
    ch = db.channel(cid)
    if not ch:
        raise HTTPException(404, "チャンネルが見つかりません")
    fam, _ = require_member(ch["family_id"], user)
    return fam, ch


@app.post("/api/families/{fid}/channels")
async def create_channel(fid: str, body: ChannelIn, user: dict = Depends(current_user)):
    fam, _ = require_member(fid, user)
    pos = len(db.channels(fid))
    ch = {"id": new_id("c_"), "family_id": fid, "name": body.name.strip()[:24] or "チャンネル", "kind": "voice" if body.kind == "voice" else "text",
          "position": pos, "created_at": now()}
    db.insert("channels", ch)
    await svc.broadcast_family(fid, {"type": "family", "family": svc.family_out(fam)})
    return {"channel": ch}


@app.delete("/api/channels/{cid}")
async def delete_channel(cid: str, user: dict = Depends(current_user)):
    fam, ch = require_channel(cid, user)
    require_owner(fam["id"], user)
    db.exec("DELETE FROM channels WHERE id=?", (cid,))
    await svc.broadcast_family(fam["id"], {"type": "family", "family": svc.family_out(fam)})
    return {"ok": True}


@app.get("/api/channels/{cid}/messages")
def list_messages(cid: str, before: int | None = None, limit: int = 50, user: dict = Depends(current_user)):
    require_channel(cid, user)
    limit = max(1, min(limit, 200))
    if before:
        rows = db.all("SELECT * FROM messages WHERE channel_id=? AND id<? ORDER BY id DESC LIMIT ?", (cid, before, limit))
    else:
        rows = db.all("SELECT * FROM messages WHERE channel_id=? ORDER BY id DESC LIMIT ?", (cid, limit))
    rows.reverse()
    acks: dict[int, list[str]] = {}
    if rows:
        for a in db.all(f"SELECT * FROM acks WHERE message_id IN ({','.join('?' * len(rows))})", [r["id"] for r in rows]):
            acks.setdefault(a["message_id"], []).append(a["user_id"])
    out = []
    for r in rows:
        m = svc.message_out(r)
        m["acks"] = acks.get(r["id"], [])
        out.append(m)
    return {"messages": out}


@app.post("/api/channels/{cid}/messages")
async def post_message(cid: str, body: MessageIn, user: dict = Depends(current_user)):
    fam, ch = require_channel(cid, user)
    if ch["kind"] != "text":
        raise HTTPException(400, "テキストチャンネルではありません")
    text = body.text.strip()
    if not text:
        raise HTTPException(400, "メッセージが空です")
    kind = body.kind if body.kind in ("text", "stamp") else "text"
    msg = await svc.post_message(fam["id"], cid, user["id"], text[:2000], kind=kind, reply_to=body.reply_to, meta=body.meta)
    if agent.is_addressed(fam, text, ch):
        asyncio.get_running_loop().create_task(_ai_reply(fam["id"], user["id"], text, cid))
    return {"message": msg}


async def _ai_reply(fid: str, uid: str, text: str, cid: str) -> None:
    try:
        await agent.handle(fid, uid, text, cid)
    except Exception:
        log.exception("AI failed")
        await svc.ai_say(fid, "ごめん、ちょっと調子が悪いみたい…🙏", cid=cid, emotion="sad", reasons=["内部エラー"])


@app.post("/api/channels/{cid}/voice")
async def post_voice(cid: str, file: UploadFile = File(...), duration: float = Form(0), text: str = Form(""), user: dict = Depends(current_user)):
    fam, ch = require_channel(cid, user)
    MEDIA.mkdir(parents=True, exist_ok=True)
    ext = Path(file.filename or "a.m4a").suffix.lower() or ".m4a"
    if ext not in (".m4a", ".aac", ".mp4", ".webm", ".ogg", ".mp3", ".wav", ".3gp"):
        ext = ".m4a"
    name = new_id("v_") + ext
    with (MEDIA / name).open("wb") as f:
        shutil.copyfileobj(file.file, f)
    label = text.strip() or "🎤 ボイスメッセージ"
    msg = await svc.post_message(fam["id"], cid, user["id"], label, kind="voice", media=f"/media/{name}", meta={"duration": duration})
    return {"message": msg}


@app.post("/api/messages/{mid}/ack")
async def ack_message(mid: int, user: dict = Depends(current_user)):
    msg = db.message(mid)
    if not msg:
        raise HTTPException(404)
    require_member(msg["family_id"], user)
    await svc.ack(user["id"], mid)
    return {"ok": True}


@app.post("/api/channels/{cid}/read")
async def read_channel(cid: str, user: dict = Depends(current_user)):
    fam, ch = require_channel(cid, user)
    last = db.one("SELECT MAX(id) AS id FROM messages WHERE channel_id=?", (cid,))["id"] or 0
    svc.mark_read(user["id"], cid, last)
    for mid, p in list(svc.pending_urgent.items()):
        if p["cid"] == cid and user["id"] in p["waiting"] and mid <= last:
            await svc.ack(user["id"], mid)
    return {"ok": True, "last_id": last}


@app.get("/media/{name}")
def media(name: str):
    p = MEDIA / name
    if "/" in name or not p.exists():
        raise HTTPException(404)
    return FileResponse(p)


@app.get("/api/families/{fid}/search")
def search(fid: str, q: str, user: dict = Depends(current_user)):
    require_member(fid, user)
    return {"messages": [svc.message_out(r) for r in db.search_messages(fid, q, limit=30)]}


@app.post("/api/families/{fid}/poke")
async def poke(fid: str, body: PokeIn, user: dict = Depends(current_user)):
    require_member(fid, user)
    kinds = {"poke": ("つんつん", "surprised", "exclaim"), "pat": ("なでなで", "happy", "hearts"), "wave": ("やっほー", "happy", "sparkle"),
             "cheer": ("がんばれ！", "excited", "confetti")}
    label, emo, fx = kinds.get(body.kind, kinds["poke"])
    name = svc.display_name(fid, user["id"])
    await hub.send_user(body.to, {"type": "poke", "family_id": fid, "from": user["id"], "kind": body.kind,
                                  "chibi": {"from": user["id"], "text": f"{name}が{label}してきたよ", "emotion": emo,
                                            "motion": {"poke": "surprised", "pat": "bothWave", "wave": "wave", "cheer": "cheer"}.get(body.kind, "wave"),
                                            "fx": fx, "replies": ["💕", "つんつん返し"], "duration": 5000}})
    return {"ok": True}


# ---------------------------------------------------------------------------------------------
# fridge memos
# ---------------------------------------------------------------------------------------------
@app.get("/api/families/{fid}/memos")
def list_memos(fid: str, user: dict = Depends(current_user)):
    require_member(fid, user)
    return {"memos": [svc.memo_out(m) for m in db.all("SELECT * FROM memos WHERE family_id=? AND party=0 ORDER BY done, created_at DESC", (fid,))]}


@app.post("/api/families/{fid}/memos")
async def add_memo(fid: str, body: MemoIn, user: dict = Depends(current_user)):
    require_member(fid, user)
    memo = await svc.create_memo(fid, user["id"], body.text, notify_at=body.notify_at, target=body.target_id, color=body.color,
                                 auto_time=body.auto_time, party=body.party)
    return {"memo": memo}


@app.post("/api/memos/parse")
def parse_memo(body: MemoIn):
    p = timeparse.parse(body.text)
    a = analyzer.analyze(p.text)
    return {"notify_at": p.ts, "label": timeparse.fmt(p.ts), "text": p.text, "matched": p.matched, "emotion": a.emotion}


@app.patch("/api/memos/{mid}")
async def patch_memo(mid: str, body: MemoPatch, user: dict = Depends(current_user)):
    memo = db.one("SELECT * FROM memos WHERE id=?", (mid,))
    if not memo:
        raise HTTPException(404)
    require_member(memo["family_id"], user)
    data = {k: v for k, v in body.model_dump().items() if v is not None}
    if "done" in data:
        data["done"] = int(data["done"])
    if "notify_at" in data:
        data["delivered"] = 0
    db.update("memos", {"id": mid}, data)
    memo = svc.memo_out(db.one("SELECT * FROM memos WHERE id=?", (mid,)))
    await svc.broadcast_family(memo["family_id"], {"type": "memo", "memo": memo})
    return {"memo": memo}


@app.delete("/api/memos/{mid}")
async def delete_memo(mid: str, user: dict = Depends(current_user)):
    memo = db.one("SELECT * FROM memos WHERE id=?", (mid,))
    if not memo:
        raise HTTPException(404)
    require_member(memo["family_id"], user)
    db.exec("DELETE FROM memos WHERE id=?", (mid,))
    await svc.broadcast_family(memo["family_id"], {"type": "memo_deleted", "id": mid})
    return {"ok": True}


# ----- birthday messages ("寄せ書き") --------------------------------------------------------------
@app.post("/api/families/{fid}/birthday/{uid}")
async def birthday_message(fid: str, uid: str, text: str = Form(""), file: UploadFile | None = File(None), user: dict = Depends(current_user)):
    """Leave a message (text and/or voice) that is delivered with the party at 0:00 on uid's birthday."""
    require_member(fid, user)
    target = db.user(uid)
    if not target or not db.member(fid, uid):
        raise HTTPException(404)
    ts = next_birthday_ts(target["birthday"]) if target["birthday"] else None
    if not ts:
        raise HTTPException(400, "誕生日が登録されていません")
    media_path = ""
    if file is not None and file.filename:
        MEDIA.mkdir(parents=True, exist_ok=True)
        name = new_id("v_") + (Path(file.filename).suffix or ".m4a")
        with (MEDIA / name).open("wb") as f:
            shutil.copyfileobj(file.file, f)
        media_path = f"/media/{name}"
    memo = await svc.create_memo(fid, user["id"], text or "🎤", notify_at=ts, target=uid, auto_time=False, party=True, media=media_path)
    return {"memo": memo, "deliver_at": timeparse.fmt(ts)}


# ---------------------------------------------------------------------------------------------
# calendar
# ---------------------------------------------------------------------------------------------
def event_visible(e: dict, viewer: str, fid: str) -> dict:
    if e["user_id"] and e["user_id"] != viewer:
        m = db.member(fid, e["user_id"])
        if (m and m["calendar_private"]) or e["private"]:
            return {**e, "title": "予定あり", "note": "", "masked": True}
    return e


@app.get("/api/families/{fid}/events")
def list_events(fid: str, start: float | None = None, end: float | None = None, user: dict = Depends(current_user)):
    require_member(fid, user)
    start = start or time.time() - 31 * 86400
    end = end or time.time() + 120 * 86400
    rows = db.all("SELECT * FROM events WHERE family_id=? AND start < ? AND COALESCE(end, start) >= ? ORDER BY start", (fid, end, start))
    return {"events": [event_visible(e, user["id"], fid) for e in rows]}


@app.post("/api/families/{fid}/events")
async def add_event(fid: str, body: EventIn, user: dict = Depends(current_user)):
    require_member(fid, user)
    ev = {"id": new_id("ev_"), "family_id": fid, "user_id": None if body.shared else user["id"], "title": body.title, "start": body.start,
          "end": body.end, "all_day": int(body.all_day), "private": int(body.private), "note": body.note, "created_at": now()}
    db.insert("events", ev)
    for uid in svc.family_member_ids(fid):
        await hub.send_user(uid, {"type": "event", "family_id": fid, "event": event_visible(ev, uid, fid)})
    return {"event": ev}


@app.delete("/api/events/{eid}")
async def delete_event(eid: str, user: dict = Depends(current_user)):
    ev = db.one("SELECT * FROM events WHERE id=?", (eid,))
    if not ev:
        raise HTTPException(404)
    require_member(ev["family_id"], user)
    if ev["user_id"] and ev["user_id"] != user["id"]:
        raise HTTPException(403)
    db.exec("DELETE FROM events WHERE id=?", (eid,))
    await svc.broadcast_family(ev["family_id"], {"type": "event_deleted", "id": eid})
    return {"ok": True}


# ---------------------------------------------------------------------------------------------
# tasks
# ---------------------------------------------------------------------------------------------
@app.get("/api/families/{fid}/tasks")
def list_tasks(fid: str, user: dict = Depends(current_user)):
    require_member(fid, user)
    return {"tasks": db.all("SELECT * FROM tasks WHERE family_id=? ORDER BY done, created_at DESC", (fid,))}


@app.post("/api/families/{fid}/tasks")
async def add_task(fid: str, body: TaskIn, user: dict = Depends(current_user)):
    require_member(fid, user)
    t = {"id": new_id("tk_"), "family_id": fid, "title": body.title, "assignee_id": body.assignee_id, "done": 0, "due": body.due,
         "created_by": user["id"], "created_at": now()}
    db.insert("tasks", t)
    await svc.broadcast_family(fid, {"type": "task", "task": t})
    return {"task": t}


@app.patch("/api/tasks/{tid}")
async def patch_task(tid: str, body: TaskPatch, user: dict = Depends(current_user)):
    t = db.one("SELECT * FROM tasks WHERE id=?", (tid,))
    if not t:
        raise HTTPException(404)
    require_member(t["family_id"], user)
    data = {k: v for k, v in body.model_dump(exclude_unset=True).items()}
    if "done" in data:
        data["done"] = int(bool(data["done"]))
    db.update("tasks", {"id": tid}, data)
    t = db.one("SELECT * FROM tasks WHERE id=?", (tid,))
    await svc.broadcast_family(t["family_id"], {"type": "task", "task": t})
    if data.get("done") and t["created_by"] and t["created_by"] != user["id"]:
        await hub.send_user(t["created_by"], {"type": "poke", "family_id": t["family_id"], "from": user["id"], "kind": "task",
                                              "chibi": {"from": user["id"], "text": f"「{t['title']}」やっておいたよ！", "emotion": "happy",
                                                        "motion": "wave", "fx": "sparkle", "replies": ["ありがとう！"]}})
    return {"task": t}


@app.delete("/api/tasks/{tid}")
async def delete_task(tid: str, user: dict = Depends(current_user)):
    t = db.one("SELECT * FROM tasks WHERE id=?", (tid,))
    if not t:
        raise HTTPException(404)
    require_member(t["family_id"], user)
    db.exec("DELETE FROM tasks WHERE id=?", (tid,))
    await svc.broadcast_family(t["family_id"], {"type": "task_deleted", "id": tid})
    return {"ok": True}


# ---------------------------------------------------------------------------------------------
# AI & asks
# ---------------------------------------------------------------------------------------------
@app.post("/api/families/{fid}/ai")
async def ask_ai(fid: str, body: AskAIIn, user: dict = Depends(current_user)):
    fam, _ = require_member(fid, user)
    if not fam["ai_enabled"]:
        raise HTTPException(400, "AIちびっこはOFFになっています")
    cid = body.channel_id or next((c["id"] for c in db.channels(fid) if c["name"] == "ちびAI"), svc.default_channel(fid))
    await svc.post_message(fid, cid, user["id"], body.text, meta={"to_ai": True})
    msg = await agent.handle(fid, user["id"], body.text, cid)
    return {"message": msg}


@app.get("/api/families/{fid}/digest")
def digest(fid: str, user: dict = Depends(current_user)):
    from .agent import Run
    require_member(fid, user)
    run = Run(fid=fid, uid=user["id"], cid="", text="")
    return {"text": agent.digest(run), "reasons": run.reasons}


@app.get("/api/families/{fid}/asks")
def list_asks(fid: str, user: dict = Depends(current_user)):
    require_member(fid, user)
    return {"asks": db.all("SELECT * FROM asks WHERE family_id=? AND target_id=? AND status='open' ORDER BY created_at", (fid, user["id"]))}


@app.post("/api/asks/{aid}/answer")
async def answer_ask(aid: str, body: AnswerIn, user: dict = Depends(current_user)):
    ask = await svc.answer_ask(aid, user["id"], body.answer)
    if not ask:
        raise HTTPException(404, "この質問には回答できません")
    return {"ask": ask}


@app.get("/api/families/{fid}/ai/status")
async def ai_status(fid: str, user: dict = Depends(current_user)):
    fam, _ = require_member(fid, user)
    s = fam["settings"] or {}
    llm = await agent.llm_available(s)
    from .mcp_hub import MCPSession
    async with MCPSession(s.get("mcp_servers") if fam["iot_enabled"] else []) as mcp:
        tools = [{"server": t.server, "name": t.name, "description": t.description} for t in mcp.tools]
        errors = mcp.errors
    return {"llm": llm, "model": s.get("model"), "ollama_url": s.get("ollama_url"), "engine": "llm" if llm and s.get("llm_enabled", True) else "rule",
            "mcp_tools": tools, "mcp_errors": errors}


# ---------------------------------------------------------------------------------------------
# smart home (the same MCP server the AI uses; manual control from the app)
# ---------------------------------------------------------------------------------------------
@app.get("/api/families/{fid}/home")
def home_state(fid: str, user: dict = Depends(current_user)):
    require_member(fid, user)
    return HOME.snapshot()


@app.post("/api/families/{fid}/home/{device}")
def home_set(fid: str, device: str, body: DeviceIn, user: dict = Depends(current_user)):
    fam, _ = require_member(fid, user)
    if not fam["iot_enabled"]:
        raise HTTPException(400, "IoT連携がOFFです")
    if device not in HOME.devices:
        raise HTTPException(404)
    HOME.set(device, **{k: v for k, v in body.model_dump().items() if v is not None})
    return HOME.snapshot()


# ---------------------------------------------------------------------------------------------
# demo director (for presentations): seeds a family and triggers the proposal's scenarios
# ---------------------------------------------------------------------------------------------
# (name, nickname, avatar, color, calendar_private, watch)
DEMO_MEMBERS = [("ママ", "母", "builtin_VRM1_Sample", "#ff7a90", False, False), ("ひまり", "妹", "builtin_AvatarSample_L", "#b57bff", False, False),
                ("パパ", "父", "builtin_AvatarSample_M", "#ff8a65", True, False), ("よしこ", "祖母", "builtin_AvatarSample_K", "#2ec4b6", False, True)]


def demo_user(fid: str, nickname: str) -> dict | None:
    return db.one("SELECT u.* FROM users u JOIN members m ON m.user_id=u.id WHERE m.family_id=? AND m.nickname=?", (fid, nickname))


@app.post("/api/demo/seed")
async def demo_seed(user: dict = Depends(current_user)):
    fam = make_family(user, "しげの家", "🏡", "兄")
    fid = fam["id"]
    used = {av for _, _, av, *_ in DEMO_MEMBERS} | {fam["ai_avatar"]}
    if user["avatar"] in used:  # give the user a look nobody else in the demo family has
        free = next((a["id"] for a in db.all("SELECT id FROM avatars WHERE builtin=1") if a["id"] not in used), user["avatar"])
        db.update("users", {"id": user["id"]}, {"avatar": free})
    for name, nick, av, color, private, watch in DEMO_MEMBERS:
        u = create_user(name, av, color, virtual=True)
        db.insert("members", {"family_id": fid, "user_id": u["id"], "role": "member", "nickname": nick, "calendar_private": int(private),
                              "watch": int(watch), "joined_at": now()})
    today = timeparse.now_local()
    sat = today.date() + __import__("datetime").timedelta(days=(5 - today.weekday()) % 7 or 7)
    sis = demo_user(fid, "妹")
    import datetime as _dt
    at = lambda d, h: _dt.datetime(d.year, d.month, d.day, h, 0, tzinfo=timeparse.TZ).timestamp()
    db.insert("events", {"id": new_id("ev_"), "family_id": fid, "user_id": sis["id"], "title": "部活の試合", "start": at(sat, 9), "end": at(sat, 13),
                         "all_day": 0, "private": 0, "note": "", "created_at": now()})
    papa = demo_user(fid, "父")
    db.insert("events", {"id": new_id("ev_"), "family_id": fid, "user_id": papa["id"], "title": "出張", "start": at(sat, 8), "end": at(sat, 18),
                         "all_day": 0, "private": 0, "note": "", "created_at": now()})
    db.insert("events", {"id": new_id("ev_"), "family_id": fid, "user_id": None, "title": "ゴミ出し（燃えるゴミ）", "start": at(today.date() + _dt.timedelta(days=1), 7),
                         "end": None, "all_day": 0, "private": 0, "note": "", "created_at": now()})
    db.insert("events", {"id": new_id("ev_"), "family_id": fid, "user_id": sis["id"], "title": "学校", "start": at(today.date(), 8),
                         "end": at(today.date(), 22), "all_day": 0, "private": 0, "note": "", "created_at": now()})
    cid = svc.default_channel(fid)
    mama = demo_user(fid, "母")
    await demo_presence(fid)
    history = [(mama, "Wi-Fiのパスワード変えたよ。新しいのは famichibi2026 です"), (papa, "来週の土曜は出張で夜まで帰れないです"),
               (sis, "明日のお弁当いらない！部活で出るから"), (mama, "了解〜")]
    for u, t in history:
        await svc.post_message(fid, cid, u["id"], t)
    db.insert("memos", {"id": new_id("mm_"), "family_id": fid, "author_id": mama["id"], "text": "牛乳買ってきてね🥛", "color": "#fff3a3",
                        "notify_at": None, "target_id": None, "emotion": "neutral", "motion": "talk", "party": 0, "media": "", "done": 0,
                        "delivered": 0, "created_at": now()})
    db.insert("tasks", {"id": new_id("tk_"), "family_id": fid, "title": "お風呂掃除", "assignee_id": user["id"], "done": 0, "due": None,
                        "created_by": mama["id"], "created_at": now()})
    return {"family": svc.family_out(db.family(fid), user["id"])}


async def demo_presence(fid: str) -> None:
    """Initial 'けはい' of the virtual demo family: mom and grandma at home, dad at work, sister at school."""
    t = time.time()
    for nick, st in (("母", {"state": "active", "at_home": True, "battery": 64}), ("祖母", {"state": "idle", "at_home": True, "battery": 48}),
                     ("父", {"state": "idle", "at_home": False, "battery": 71}), ("妹", {"state": "moving", "at_home": False, "battery": 35})):
        u = demo_user(fid, nick)
        if not u:
            continue
        p = {**st, "since": t, "home_since": t - (3 * 3600 if nick == "父" else 1800), "last_active": t - (3600 if nick == "祖母" else 60), "charging": False}
        svc.presence.data[u["id"]] = p
        db.kv_set(f"presence:{u['id']}", p)
        await svc.broadcast_family(fid, {"type": "presence", "user_id": u["id"], "online": True, "presence": svc.presence.public(u["id"], fid)})


SCENARIOS = {
    "arrive": "けはい: 妹が学校から帰宅（ドアから「ただいま」）", "leave": "けはい: 母が買い物へ（いってきます）",
    "sleep": "けはい: おばあちゃんがお昼寝", "wellness": "見守り: おばあちゃんに朝の「元気？」",
    "unwell": "見守り: おばあちゃん「調子わるい」", "inactive": "見守り: 5時間スマホ操作なし", "battery": "見守り: 電池残り9%",
    "reset": "けはいを初期状態に戻す",
    "pickup": "雨の日の迎え連絡（妹）", "curry": "冷蔵庫メモ（母・10秒後に踊って通知）", "birthday": "誕生日パーティー（家族全員）",
    "lights": "AI: 電気消して", "schedule": "AI: 土曜の予定確認（非公開の人には聞きに行く）", "love": "母: 愛情メッセージ",
    "angry": "母: 部屋片付けて！", "thanks": "父: ありがとう", "ask": "AI: パパに帰る時間を聞いて", "recall": "AI: 過去のチャット検索",
    "digest": "AI: 見逃しまとめ",
}


@app.get("/api/demo/scenarios")
def demo_scenarios():
    return {"scenarios": SCENARIOS}


@app.post("/api/families/{fid}/demo/{name}")
async def demo_run(fid: str, name: str, user: dict = Depends(current_user)):
    fam, _ = require_member(fid, user)
    cid = svc.default_channel(fid)
    ai_cid = next((c["id"] for c in db.channels(fid) if c["name"] == "ちびAI"), cid)
    mama, sis, papa = demo_user(fid, "母"), demo_user(fid, "妹"), demo_user(fid, "父")
    others = [m for m in db.members(fid) if m["user_id"] != user["id"]]
    pick = lambda u: u or (db.user(others[0]["user_id"]) if others else user)
    baba = demo_user(fid, "祖母")
    P = svc.presence
    if name == "arrive":
        await P.report(pick(sis)["id"], {"at_home": True, "state": "active"}, virtual=True)
    elif name == "leave":
        await P.report(pick(mama)["id"], {"at_home": False, "state": "moving"}, virtual=True)
    elif name == "sleep":
        await P.report(pick(baba)["id"], {"state": "sleep"}, virtual=True)
    elif name == "wellness":
        await P.morning_check(fid, pick(baba)["id"])
    elif name == "unwell":
        await P.wellness_answer(fid, pick(baba)["id"], "ちょっと調子わるい")
    elif name == "inactive":
        u = pick(baba)
        P.get(u["id"])["last_active"] = time.time() - 5 * 3600
        await P._alert(fid, u["id"], "5時間くらいスマホを触っていないみたい。", ["見守り設定: 日中4時間以上操作なし"])
    elif name == "battery":
        await P.report(pick(baba)["id"], {"battery": 9, "charging": False}, virtual=True)
    elif name == "reset":
        await demo_presence(fid)
    elif name == "pickup":
        await svc.post_message(fid, cid, pick(sis)["id"], "今から帰る。駅まで迎えに来て！雨やばい☔")
    elif name == "curry":
        await svc.create_memo(fid, pick(mama)["id"], "カレー作っておいたから食べてね🍛 温めるときはラップ外してね", notify_at=time.time() + 10, auto_time=False)
    elif name == "birthday":
        for u, t in ((mama, "お誕生日おめでとう！一人暮らしがんばってるね。ちゃんとご飯食べてる？🎂"), (sis, "お兄ちゃんおめでと〜！🎉 お土産よろしく"),
                     (papa, "誕生日おめでとう。たまには帰っておいで")):
            u = pick(u)
            db.insert("memos", {"id": new_id("mm_"), "family_id": fid, "author_id": u["id"], "text": t, "color": "#ffd6e0",
                                "notify_at": time.time() + 3, "target_id": user["id"], "emotion": "excited", "motion": "cheer", "party": 1,
                                "media": "", "done": 0, "delivered": 0, "created_at": now()})
    elif name in ("lights", "schedule", "ask", "recall", "digest"):
        q = {"lights": "電気消して", "schedule": "今度の土曜日、みんな空いてる？", "ask": "パパに何時に帰ってくるか聞いてきて",
             "recall": "前にWi-Fiのパスワードなんて言ってた？", "digest": "見逃した連絡まとめて"}[name]
        await svc.post_message(fid, ai_cid, user["id"], q, meta={"to_ai": True})
        asyncio.get_running_loop().create_task(_ai_reply(fid, user["id"], q, ai_cid))
    elif name == "love":
        await svc.post_message(fid, cid, pick(mama)["id"], "今日もお仕事おつかれさま！大好きだよ❤")
    elif name == "angry":
        await svc.post_message(fid, cid, pick(mama)["id"], "いい加減に部屋片付けて！💢 何回言ったらわかるの")
    elif name == "thanks":
        await svc.post_message(fid, cid, pick(papa)["id"], "迎えありがとう、助かったよ🙏")
    else:
        raise HTTPException(404, "unknown scenario")
    return {"ok": True, "scenario": SCENARIOS.get(name)}


@app.post("/api/families/{fid}/demo-say")
async def demo_say(fid: str, nickname: str = Form(...), text: str = Form(...), user: dict = Depends(current_user)):
    require_member(fid, user)
    u = demo_user(fid, nickname)
    if not u:
        raise HTTPException(404)
    msg = await svc.post_message(fid, svc.default_channel(fid), u["id"], text)
    return {"message": msg}


# ---------------------------------------------------------------------------------------------
# WebSocket
# ---------------------------------------------------------------------------------------------
@app.websocket("/ws")
async def ws_endpoint(ws: WebSocket, token: str = ""):
    user = db.user_by_token(token) if token else None
    if not user:
        await ws.close(code=4401)
        return
    await ws.accept()
    uid = user["id"]
    hub.add(uid, ws)
    fams = db.families_of(uid)
    await ws.send_text(json.dumps({"type": "hello", "user": svc.user_public(user),
                                   "families": [svc.family_out(f, uid) for f in fams],
                                   "voice": {cid: sorted(u) for cid, u in hub.voice.items() if u},
                                   "asks": db.all("SELECT * FROM asks WHERE target_id=? AND status='open'", (uid,)),
                                   "home": HOME.snapshot(), "time": time.time()}, ensure_ascii=False))
    for f in fams:
        await svc.broadcast_family(f["id"], {"type": "presence", "user_id": uid, "online": True}, exclude=uid)
    try:
        while True:
            raw = await ws.receive_text()
            try:
                ev = json.loads(raw)
            except ValueError:
                continue
            t = ev.get("type")
            if t == "ping":
                await ws.send_text(json.dumps({"type": "pong", "time": time.time()}))
            elif t == "typing":
                ch = db.channel(ev.get("channel_id", ""))
                if ch and db.member(ch["family_id"], uid):
                    await svc.broadcast_family(ch["family_id"], {"type": "typing", "channel_id": ch["id"], "user_id": uid}, exclude=uid)
            elif t == "ack":
                await svc.ack(uid, int(ev.get("message_id", 0)))
            elif t == "voice_join":
                ch = db.channel(ev.get("channel_id", ""))
                if ch and ch["kind"] == "voice" and db.member(ch["family_id"], uid):
                    for c, users in hub.voice.items():
                        users.discard(uid)
                    hub.voice[ch["id"]].add(uid)
                    await svc.broadcast_family(ch["family_id"], {"type": "voice_state", "channel_id": ch["id"], "users": sorted(hub.voice[ch["id"]]), "joined": uid})
            elif t == "presence_report":
                await svc.presence.report(uid, ev)
            elif t == "voice_leave":
                await _leave_voice(uid)
            elif t == "rtc":
                to = ev.get("to")
                if to and any(uid in users and to in users for users in hub.voice.values()):
                    await hub.send_user(to, {"type": "rtc", "from": uid, "data": ev.get("data"), "channel_id": ev.get("channel_id")})
    except WebSocketDisconnect:
        pass
    except Exception:
        log.exception("ws error")
    finally:
        hub.remove(uid, ws)
        if not hub.online(uid):
            await _leave_voice(uid)
            for f in db.families_of(uid):
                await svc.broadcast_family(f["id"], {"type": "presence", "user_id": uid, "online": False}, exclude=uid)


async def _leave_voice(uid: str) -> None:
    for cid, users in list(hub.voice.items()):
        if uid in users:
            users.discard(uid)
            ch = db.channel(cid)
            if ch:
                await svc.broadcast_family(ch["family_id"], {"type": "voice_state", "channel_id": cid, "users": sorted(users), "left": uid})


# ---------------------------------------------------------------------------------------------
# web client & static
# ---------------------------------------------------------------------------------------------
@app.get("/join/{tok}", response_class=HTMLResponse)
def join_page(tok: str, request: Request):
    inv = db.one("SELECT * FROM invites WHERE token=?", (tok,))
    fam = db.family(inv["family_id"]) if inv else None
    if not fam:
        return HTMLResponse("<h1>招待が見つかりません</h1>", status_code=404)
    link = f"famichibi://join?host={public_base(request)}&invite={tok}"
    return HTMLResponse(f"""<!doctype html><meta charset=utf-8><meta name=viewport content="width=device-width,initial-scale=1">
<title>{fam['name']}への招待</title><body style="font-family:sans-serif;text-align:center;padding:24px;background:#fff8f2">
<h2>{fam['emoji']} {fam['name']} に招待されています</h2><p>FamiChibiアプリで開くか、QRを読み取ってください</p>
<p><a href="{link}" style="display:inline-block;padding:14px 24px;background:#ff8a65;color:#fff;border-radius:999px;text-decoration:none">アプリで参加</a></p>
<img src="/api/qr?data={urllib.parse.quote(link, safe='')}" width=220><p><a href="/?invite={tok}">ブラウザで参加</a></p></body>""")


app.mount("/stage", StaticFiles(directory=STATIC / "stage", html=True), name="stage")
if (STATIC / "web").exists():
    app.mount("/", StaticFiles(directory=STATIC / "web", html=True), name="web")


def run() -> None:
    import uvicorn
    ap = argparse.ArgumentParser()
    ap.add_argument("--host", default=os.environ.get("FAMICHIBI_HOST", "0.0.0.0"))
    ap.add_argument("--port", type=int, default=int(os.environ.get("FAMICHIBI_PORT", "8000")))
    ap.add_argument("--name", default="")
    a = ap.parse_args()
    if a.name:
        db.kv_set("host_name", a.name)
    MAIN_LOOP["port"] = a.port
    print(f"\n  FamiChibi host  ->  http://{local_ip_for()}:{a.port}\n")
    uvicorn.run(app, host=a.host, port=a.port, log_level="info")


if __name__ == "__main__":
    run()
