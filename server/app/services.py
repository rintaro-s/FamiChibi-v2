"""Domain operations shared by the HTTP routes, the WebSocket handler, the scheduler and the AI."""
from __future__ import annotations

import asyncio
import logging
import time
from datetime import datetime, timedelta

from . import analyzer, timeparse
from .db import DB, new_id, now
from .hub import Hub

log = logging.getLogger("famichibi")

AI_ID = "ai"
# Escalation for urgent messages nobody reacted to (seconds)
NUDGE_AFTER = 60
NUDGE_REPEAT = 60
ESCALATE_AFTER = 150


def hm(s: str) -> int | None:
    try:
        h, m = s.split(":")
        return int(h) * 60 + int(m)
    except (ValueError, AttributeError):
        return None


def in_quiet_hours(member: dict, at: datetime | None = None) -> bool:
    a, b = hm(member.get("quiet_start", "")), hm(member.get("quiet_end", ""))
    if a is None or b is None or a == b:
        return False
    at = at or timeparse.now_local()
    cur = at.hour * 60 + at.minute
    return a <= cur < b if a < b else (cur >= a or cur < b)


class Services:
    def __init__(self, db: DB, hub: Hub):
        self.db = db
        self.hub = hub
        self.pending_urgent: dict[int, dict] = {}
        self.agent = None  # set by main (avoid import cycle)
        from .presence import Presence
        self.presence = Presence(self)

    # ------------------------------------------------------------------ serialization
    def user_public(self, u: dict | None) -> dict | None:
        if not u:
            return None
        return {k: u.get(k) for k in ("id", "name", "avatar", "color", "birthday", "voice_pitch")}

    def member_out(self, m: dict) -> dict:
        return {
            "user_id": m["user_id"], "name": m["name"], "nickname": m["nickname"], "role": m["role"],
            "avatar": m["avatar"], "color": m["color"], "birthday": m.get("birthday", ""),
            "voice_pitch": m.get("voice_pitch", 1.0), "calendar_private": bool(m["calendar_private"]),
            "quiet_start": m["quiet_start"], "quiet_end": m["quiet_end"], "online": self.hub.online(m["user_id"]),
            "watch": bool(m.get("watch", 0)), "share_presence": bool(m.get("share_presence", 1)),
            "presence": self.presence.public(m["user_id"], m["family_id"]) if m.get("share_presence", 1) else {},
        }

    def ai_member(self, fam: dict) -> dict:
        return {
            "user_id": AI_ID, "name": fam["ai_name"], "nickname": fam["ai_name"], "role": "ai", "avatar": fam["ai_avatar"],
            "color": "#5ec8ff", "birthday": "", "voice_pitch": 1.25, "calendar_private": False, "quiet_start": "", "quiet_end": "",
            "online": True, "ai": True,
        }

    def family_out(self, fam: dict, uid: str | None = None) -> dict:
        members = [self.member_out(m) for m in self.db.members(fam["id"])]
        settings = dict(fam["settings"] or {})
        out = {
            "id": fam["id"], "name": fam["name"], "emoji": fam["emoji"], "code": fam["code"], "owner_id": fam["owner_id"],
            "ai_enabled": bool(fam["ai_enabled"]), "iot_enabled": bool(fam["iot_enabled"]), "ai_name": fam["ai_name"],
            "ai_avatar": fam["ai_avatar"], "members": members, "channels": self.db.channels(fam["id"]),
            "ai": self.ai_member(fam) if fam["ai_enabled"] else None,
            "settings": {k: v for k, v in settings.items() if k != "secrets"},
        }
        if uid:
            me = next((m for m in members if m["user_id"] == uid), None)
            out["me"] = me
            out["unread"] = self.unread_counts(fam["id"], uid)
        return out

    def message_out(self, m: dict) -> dict:
        return {k: m.get(k) for k in ("id", "channel_id", "family_id", "user_id", "kind", "text", "media", "analysis", "meta", "reply_to", "created_at")}

    def family_member_ids(self, fid: str) -> list[str]:
        return [r["user_id"] for r in self.db.all("SELECT user_id FROM members WHERE family_id=?", (fid,))]

    async def broadcast_family(self, fid: str, event: dict, exclude: str | None = None) -> None:
        await self.hub.send_users([u for u in self.family_member_ids(fid) if u != exclude], {"family_id": fid, **event})

    # ------------------------------------------------------------------ messages
    def notify_plan(self, member: dict, analysis: dict, sender_id: str | None) -> dict:
        """Per-recipient delivery decision ("AIが通知タイミングを補助").

        mode: self | urgent | now | quiet
        """
        if member["user_id"] == sender_id:
            return {"mode": "self", "reason": ""}
        urg = analysis.get("urgency", "normal")
        quiet = in_quiet_hours(member)
        if urg == "high":
            return {"mode": "urgent", "reason": "急ぎの連絡なので、おやすみ時間でもお知らせします" if quiet else "急ぎの連絡です（ロック画面にも表示）"}
        if quiet:
            return {"mode": "quiet", "reason": f"おやすみ時間（{member['quiet_start']}〜{member['quiet_end']}）なので静かに届けます"}
        if urg == "low":
            return {"mode": "quiet", "reason": "短い返事なので控えめに届けます"}
        return {"mode": "now", "reason": ""}

    async def post_message(
        self, fid: str, cid: str, uid: str | None, text: str, *, kind: str = "text", media: str = "",
        meta: dict | None = None, analysis: dict | None = None, reply_to: int | None = None, skip_notify: set | None = None,
    ) -> dict:
        text = (text or "").strip()
        if analysis is None:
            analysis = analyzer.analyze(text, kind).to_dict() if kind in ("text", "voice", "memo") else {}
        row = self.db.insert("messages", {
            "channel_id": cid, "family_id": fid, "user_id": uid, "kind": kind, "text": text, "media": media,
            "analysis": analysis, "meta": meta or {}, "reply_to": reply_to, "created_at": now(),
        })
        msg = self.message_out(self.db.message(row["rowid"]))
        members = self.db.members(fid)
        for m in members:
            plan = {"mode": "self", "reason": ""} if skip_notify and m["user_id"] in skip_notify else self.notify_plan(m, analysis, uid)
            await self.hub.send_user(m["user_id"], {"type": "message", "family_id": fid, "channel_id": cid, "message": msg, "notify": plan})
        if analysis.get("urgency") == "high" and uid:
            self.pending_urgent[msg["id"]] = {
                "fid": fid, "cid": cid, "sender": uid, "at": time.time(), "nudged": {}, "escalated": False,
                "waiting": {m["user_id"] for m in members if m["user_id"] != uid},
            }
        if uid:
            self.mark_read(uid, cid, msg["id"])
        if analysis.get("party") and uid:
            await self.broadcast_family(fid, {"type": "party", "reason": text, "by": uid, "for": None})
        return msg

    def mark_read(self, uid: str, cid: str, last_id: int) -> None:
        self.db.exec(
            "INSERT INTO reads(user_id, channel_id, last_id) VALUES(?,?,?) ON CONFLICT(user_id, channel_id) DO UPDATE SET last_id=MAX(last_id, excluded.last_id)",
            (uid, cid, last_id),
        )

    def unread_counts(self, fid: str, uid: str) -> dict:
        rows = self.db.all(
            """SELECT c.id AS cid, COUNT(m.id) AS n FROM channels c
               LEFT JOIN reads r ON r.channel_id=c.id AND r.user_id=?
               LEFT JOIN messages m ON m.channel_id=c.id AND m.id > COALESCE(r.last_id, 0) AND COALESCE(m.user_id,'') != ?
               WHERE c.family_id=? GROUP BY c.id""",
            (uid, uid, fid),
        )
        return {r["cid"]: r["n"] for r in rows}

    async def ack(self, uid: str, mid: int) -> None:
        msg = self.db.message(mid)
        if not msg:
            return
        self.db.exec("INSERT OR IGNORE INTO acks(message_id, user_id, at) VALUES(?,?,?)", (mid, uid, now()))
        self.mark_read(uid, msg["channel_id"], mid)
        p = self.pending_urgent.get(mid)
        if p and uid in p["waiting"]:
            self.pending_urgent.pop(mid, None)
            if msg["user_id"]:
                who = self.display_name(msg["family_id"], uid)
                await self.hub.send_user(msg["user_id"], {"type": "acked", "family_id": msg["family_id"], "message_id": mid, "by": uid,
                                                          "text": f"{who}が見たよ！"})
        await self.broadcast_family(msg["family_id"], {"type": "ack", "message_id": mid, "user_id": uid})

    def display_name(self, fid: str, uid: str | None) -> str:
        if not uid or uid == AI_ID:
            fam = self.db.family(fid)
            return fam["ai_name"] if fam else "ちびAI"
        m = self.db.one("SELECT m.nickname, u.name FROM members m JOIN users u ON u.id=m.user_id WHERE m.family_id=? AND m.user_id=?", (fid, uid))
        if not m:
            u = self.db.user(uid)
            return u["name"] if u else "だれか"
        return m["nickname"] or m["name"]

    def default_channel(self, fid: str) -> str:
        ch = self.db.one("SELECT id FROM channels WHERE family_id=? AND kind='text' ORDER BY position, created_at LIMIT 1", (fid,))
        return ch["id"] if ch else ""

    async def ai_say(self, fid: str, text: str, *, cid: str | None = None, reasons: list[str] | None = None,
                     emotion: str = "happy", motion: str | None = None, extra: dict | None = None, fx: str | None = None,
                     urgency: str = "normal", exclude: str | None = None) -> dict:
        cid = cid or self.default_channel(fid)
        motion = motion or analyzer.MOTION_BY_EMOTION.get(emotion, "talk")
        replies = ["電話する", "様子を見に行く", "了解"] if urgency == "high" else []
        analysis = {"emotion": emotion, "motion": motion, "fx": fx or analyzer.FX_BY_EMOTION.get(emotion), "urgency": urgency,
                    "replies": replies, "reasons": reasons or [], "party": False}
        return await self.post_message(fid, cid, None, text, kind="ai", analysis=analysis, meta={"ai": True, **(extra or {})},
                                       skip_notify={exclude} if exclude else None)

    # ------------------------------------------------------------------ memos (fridge)
    def memo_out(self, m: dict) -> dict:
        d = dict(m)
        d["notify_label"] = timeparse.fmt(m.get("notify_at"))
        return d

    async def create_memo(self, fid: str, author: str | None, text: str, *, notify_at: float | None = None, target: str | None = None,
                          color: str = "#fff3a3", auto_time: bool = True, party: bool = False, media: str = "") -> dict:
        parsed_label = ""
        if notify_at is None and auto_time:
            p = timeparse.parse(text)
            if p.when and p.when.timestamp() > time.time() - 60:
                notify_at = p.when.timestamp()
                parsed_label = p.matched
                text = p.text or text
        a = analyzer.analyze(text)
        motion = a.motion
        if notify_at and a.emotion in ("love", "happy", "excited", "neutral"):
            motion = "dance"  # "15日の16時に母ちびっこが踊りながら通知します"
        memo = {
            "id": new_id("mm_"), "family_id": fid, "author_id": author, "text": text, "color": color, "notify_at": notify_at,
            "target_id": target, "emotion": a.emotion, "motion": motion, "party": int(party), "media": media, "done": 0,
            "delivered": 0, "created_at": now(),
        }
        self.db.insert("memos", memo)
        out = self.memo_out(memo)
        out["parsed"] = parsed_label
        await self.broadcast_family(fid, {"type": "memo", "memo": out})
        return out

    async def deliver_memo(self, memo: dict) -> None:
        self.db.update("memos", {"id": memo["id"]}, {"delivered": 1})
        fid = memo["family_id"]
        out = self.memo_out(memo)
        targets = [memo["target_id"]] if memo.get("target_id") else self.family_member_ids(fid)
        a = analyzer.analyze(memo["text"])
        event = {
            "type": "memo_due", "family_id": fid, "memo": out,
            "chibi": {"from": memo["author_id"] or AI_ID, "text": memo["text"], "emotion": memo["emotion"] or a.emotion,
                      "motion": memo["motion"] or "dance", "fx": "notes", "replies": ["ありがとう！", "了解！"],
                      "reasons": [f"冷蔵庫メモの通知時刻（{out['notify_label']}）になりました"]},
        }
        await self.hub.send_users([t for t in targets if t], event)

    # ------------------------------------------------------------------ asks (AI asks a member on someone's behalf)
    async def create_asks(self, fid: str, requester: str | None, targets: list[str], question: str, options: list[str],
                          cid: str | None = None, context: dict | None = None) -> str:
        group = new_id("ag_")
        for t in targets:
            ask = {
                "id": new_id("ak_"), "family_id": fid, "channel_id": cid or self.default_channel(fid), "group_id": group,
                "requester_id": requester, "target_id": t, "question": question, "options": options, "status": "open",
                "context": context or {}, "created_at": now(),
            }
            self.db.insert("asks", ask)
            req_name = self.display_name(fid, requester) if requester else "ちびAI"
            await self.hub.send_user(t, {
                "type": "ask", "family_id": fid, "ask": ask,
                "chibi": {"from": AI_ID, "text": question if not requester else f"{req_name}から質問だよ：{question}", "emotion": "happy",
                          "motion": "wave" if not requester else "point",
                          "fx": "sparkle" if not requester else "question", "replies": options,
                          "reasons": [f"{req_name}の依頼でAIが代わりに聞いています"] if requester else ["見守り設定: 毎朝のあいさつ（返事は家族に伝わります）"]},
            })
            await self._virtual_answer(ask)
        return group

    async def answer_ask(self, ask_id: str, uid: str, answer: str) -> dict | None:
        ask = self.db.one("SELECT * FROM asks WHERE id=?", (ask_id,))
        if not ask or ask["target_id"] != uid or ask["status"] != "open":
            return None
        self.db.update("asks", {"id": ask_id}, {"answer": answer, "status": "answered", "answered_at": now()})
        ask = self.db.one("SELECT * FROM asks WHERE id=?", (ask_id,))
        group = self.db.all("SELECT * FROM asks WHERE group_id=?", (ask["group_id"],))
        fid = ask["family_id"]
        await self.broadcast_family(fid, {"type": "ask_answer", "ask": ask})
        if (ask.get("context") or {}).get("kind") == "wellness":
            await self.presence.wellness_answer(fid, uid, answer)
            return ask
        if all(a["status"] != "open" for a in group):
            lines = [f"・{self.display_name(fid, a['target_id'])}：{a['answer']}" for a in group]
            ctx = ask.get("context") or {}
            head = ctx.get("summary_head") or f"「{ask['question']}」のみんなの答え"
            text = head + "\n" + "\n".join(lines)
            if ctx.get("known"):
                text += "\n" + ctx["known"]
            await self.ai_say(fid, text, cid=ask["channel_id"], emotion="happy", motion="bothWave",
                              reasons=["各メンバーに直接聞いた回答をそのまま集計しています（AIは推測で代弁しません）"])
        return ask

    async def _virtual_answer(self, ask: dict) -> None:
        """Demo family members (virtual users) answer after a short delay so the flow completes on stage."""
        u = self.db.user(ask["target_id"])
        if not u or not u["token"].startswith("virtual:"):
            return
        replies = {"空いてる": "空いてるよ！", "予定ある": "その日は仕事だけど、夜なら大丈夫"}
        opts = ask.get("options") or []

        async def later():
            await asyncio.sleep(4 + hash(ask["id"]) % 4)
            ans = opts[0] if opts else "OK"
            if "パパ" in (self.display_name(ask["family_id"], ask["target_id"]) or "") and len(opts) > 1:
                ans = opts[1]
            await self.answer_ask(ask["id"], ask["target_id"], replies.get(ans, ans))
        asyncio.get_running_loop().create_task(later())

    # ------------------------------------------------------------------ scheduler
    async def tick(self) -> None:
        t = time.time()
        # due memos
        for memo in self.db.all("SELECT * FROM memos WHERE delivered=0 AND notify_at IS NOT NULL AND notify_at <= ?", (t,)):
            try:
                if memo["party"]:
                    await self.deliver_party_memos(memo)
                else:
                    await self.deliver_memo(memo)
            except Exception:
                log.exception("memo delivery failed")
        # urgent message escalation ("見逃し対策")
        for mid, p in list(self.pending_urgent.items()):
            age = t - p["at"]
            if age > 1800:
                self.pending_urgent.pop(mid, None)
                continue
            msg = self.db.message(mid)
            if not msg:
                self.pending_urgent.pop(mid, None)
                continue
            for uid in p["waiting"]:
                last = p["nudged"].get(uid, p["at"])
                if t - last >= (NUDGE_AFTER if uid not in p["nudged"] else NUDGE_REPEAT):
                    p["nudged"][uid] = t
                    await self.hub.send_user(uid, {
                        "type": "nudge", "family_id": p["fid"], "message": self.message_out(msg),
                        "chibi": {"from": msg["user_id"], "text": msg["text"], "emotion": "worried", "motion": "knock", "fx": "exclaim",
                                  "urgent": True, "replies": (msg["analysis"] or {}).get("replies", []),
                                  "reasons": [f"急ぎの連絡が{int(age // 60) or 1}分以上未読のため、もう一度お知らせしています"]},
                    })
            if age >= ESCALATE_AFTER and not p["escalated"]:
                p["escalated"] = True
                sender = self.display_name(p["fid"], p["sender"])
                await self.ai_say(
                    p["fid"], f"{sender}の「{msg['text'][:30]}」、まだ誰も見てないみたい…。気づいた人は返事してあげてね！",
                    cid=p["cid"], emotion="worried", motion="knock",
                    reasons=[f"急ぎと判断した連絡が{ESCALATE_AFTER // 60}分以上だれにも既読になっていません", "家族全員にもう一度知らせています"],
                )

    async def deliver_party_memos(self, first: dict) -> None:
        fid, target = first["family_id"], first["target_id"]
        memos = self.db.all(
            "SELECT * FROM memos WHERE family_id=? AND party=1 AND delivered=0 AND notify_at <= ? AND COALESCE(target_id,'')=COALESCE(?, '')",
            (fid, time.time() + 1, target),
        )
        for m in memos:
            self.db.update("memos", {"id": m["id"]}, {"delivered": 1})
        await self.start_party(fid, target, [self.memo_out(m) for m in memos])

    async def start_party(self, fid: str, for_user: str | None, memos: list[dict] | None = None) -> None:
        name = self.display_name(fid, for_user) if for_user else "みんな"
        messages = [{"from": m["author_id"], "text": m["text"], "media": m.get("media", "")} for m in (memos or [])]
        event = {"type": "party", "for": for_user, "title": f"🎂 {name}、お誕生日おめでとう！", "messages": messages}
        await self.broadcast_family(fid, event)

    async def birthday_check(self) -> None:
        today = timeparse.now_local().strftime("%m-%d")
        key = f"birthday:{timeparse.now_local().date().isoformat()}"
        done = set(self.db.kv_get(key, []))
        for row in self.db.all(
            "SELECT m.family_id, m.user_id FROM members m JOIN users u ON u.id=m.user_id WHERE u.birthday=?", (today,)
        ):
            k = f"{row['family_id']}:{row['user_id']}"
            if k in done:
                continue
            done.add(k)
            self.db.kv_set(key, sorted(done))
            pending = self.db.one(
                "SELECT COUNT(*) AS n FROM memos WHERE family_id=? AND target_id=? AND party=1 AND delivered=0", (row["family_id"], row["user_id"]))
            if pending and pending["n"]:
                continue  # the party memos will start the party at their own time
            await self.start_party(row["family_id"], row["user_id"])
            await self.ai_say(row["family_id"], f"🎂 今日は{self.display_name(row['family_id'], row['user_id'])}の誕生日！みんなでお祝いしよう！",
                              emotion="excited", motion="cheer", fx="confetti", reasons=["プロフィールの誕生日が今日です"])


def next_birthday_ts(mmdd: str) -> float | None:
    try:
        mo, d = (int(x) for x in mmdd.split("-"))
    except ValueError:
        return None
    n = timeparse.now_local()
    cand = datetime(n.year, mo, d, tzinfo=timeparse.TZ)
    if cand < n - timedelta(hours=23):
        cand = datetime(n.year + 1, mo, d, tzinfo=timeparse.TZ)
    return cand.timestamp()
