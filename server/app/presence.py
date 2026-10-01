"""Family presence ("けはい") and gentle watching over (見守り).

Phones report a tiny, coarse status - never location, never content:
    state    : active (using the phone) | idle | moving (walking) | sleep
    at_home  : the phone can reach this home server on the home LAN (True / False / None=unknown)
    battery  : percent, charging

From that the server derives what makes the app feel like the family is "there":
  * おかえり / いってきます   - at_home transitions become chibis walking in / out of everyone's living room
  * 生活リズム                 - sleeping members doze on the sofa, people out appear on the おでかけボード
  * 見守り (watched members)   - a friendly morning "元気？" check, and a heads-up to the family when a
                                 watched grandparent has not touched the phone for hours, or the battery is low
Every automatic notice says *why* it was sent (reasons), like the AI chibi.
"""
from __future__ import annotations

import logging
import time
from datetime import datetime

from . import timeparse
from .db import now

log = logging.getLogger("famichibi.presence")

STATES = ("active", "idle", "moving", "sleep")
DEFAULT_WATCH = {"check_time": "09:00", "alert_hours": 4, "day_start": 7, "day_end": 21}


class Presence:
    def __init__(self, svc):
        self.svc = svc
        self.db = svc.db
        self.data: dict[str, dict] = {}

    # ------------------------------------------------------------------ state
    def get(self, uid: str) -> dict:
        p = self.data.get(uid)
        if p is None:
            p = self.db.kv_get(f"presence:{uid}") or {}
            self.data[uid] = p
        return p

    def public(self, uid: str, fid: str | None = None) -> dict:
        p = self.get(uid)
        if not p:
            return {}
        out = {k: p.get(k) for k in ("state", "at_home", "battery", "charging", "since", "home_since", "last_active")}
        out["label"] = self.label(uid, fid) if fid else ""
        return out

    def label(self, uid: str, fid: str) -> str:
        """What to write on the おでかけボード: the current calendar event if any (公開カレンダーのみ)."""
        p = self.get(uid)
        if p.get("at_home") is not False:
            return ""
        m = self.db.member(fid, uid)
        t = time.time()
        if m and not m["calendar_private"]:
            ev = self.db.one("SELECT * FROM events WHERE family_id=? AND user_id=? AND private=0 AND start<=? AND COALESCE(end, start+3600)>=? ORDER BY start DESC LIMIT 1",
                             (fid, uid, t + 600, t))
            if ev:
                end = datetime.fromtimestamp(ev["end"], timeparse.TZ).strftime("%H:%M") if ev["end"] else ""
                return f"{ev['title']}" + (f"（〜{end}）" if end else "")
        if p.get("state") == "moving":
            return "移動中"
        since = p.get("home_since")
        if since:
            mins = int((t - since) // 60)
            return f"{mins // 60}時間前から" if mins >= 60 else f"{max(1, mins)}分前から"
        return "おでかけ中"

    async def report(self, uid: str, data: dict, *, virtual: bool = False) -> None:
        p = dict(self.get(uid))
        prev = dict(p)
        t = now()
        state = data.get("state")
        if state in STATES and state != p.get("state"):
            p["state"] = state
            p["since"] = t
        if state == "active":
            p["last_active"] = t
        if "at_home" in data and data["at_home"] is not None and data["at_home"] != p.get("at_home"):
            p["at_home"] = bool(data["at_home"])
            p["home_since"] = t
        for k in ("battery", "charging"):
            if k in data and data[k] is not None:
                p[k] = data[k]
        p["updated"] = t
        self.data[uid] = p
        self.db.kv_set(f"presence:{uid}", p)
        for fam in self.db.families_of(uid):
            m = self.db.member(fam["id"], uid)
            if not m or not m.get("share_presence", 1):
                continue
            fid = fam["id"]
            await self.svc.broadcast_family(fid, {"type": "presence", "user_id": uid, "online": self.svc.hub.online(uid) or virtual,
                                                  "presence": self.public(uid, fid)})
            if prev.get("at_home") is not None and "at_home" in p and prev.get("at_home") != p.get("at_home"):
                await self._home_change(fid, uid, p["at_home"])
            if state == "sleep" and prev.get("state") != "sleep":
                self.timeline(fid, uid, "sleep", "おやすみ中")
            if m.get("watch") and p.get("battery") is not None and p["battery"] <= 15 and not p.get("charging"):
                await self._battery_low(fid, uid, p["battery"])

    # ------------------------------------------------------------------ transitions
    def timeline(self, fid: str, uid: str | None, kind: str, text: str) -> None:
        key = f"timeline:{fid}"
        items = self.db.kv_get(key, [])
        items.append({"at": now(), "user_id": uid, "kind": kind, "text": text})
        self.db.kv_set(key, items[-80:])

    async def _home_change(self, fid: str, uid: str, home: bool) -> None:
        name = self.svc.display_name(fid, uid)
        if home:
            text, replies, kind = "ただいま！", ["おかえり！", "おつかれさま"], "arrive"
            reason = "おうちのWi-Fi（家族サーバー）につながったので帰宅と判断"
        else:
            text, replies, kind = "いってきます！", ["いってらっしゃい！", "気をつけてね"], "leave"
            reason = "おうちのWi-Fiから離れたので外出と判断"
        self.timeline(fid, uid, kind, f"{name}が{'帰宅' if home else '外出'}")
        others = [u for u in self.svc.family_member_ids(fid) if u != uid]
        await self.svc.hub.send_users(others, {
            "type": kind, "family_id": fid, "user_id": uid, "presence": self.public(uid, fid),
            "chibi": {"from": uid, "text": text, "emotion": "happy", "motion": "bothWave" if home else "wave",
                      "fx": "sparkle", "replies": replies, "reasons": [reason, "本人は何も操作していません（自動のお知らせ）"]},
        })

    async def _battery_low(self, fid: str, uid: str, level: int) -> None:
        key = f"watch:battery:{fid}:{uid}"
        last = self.db.kv_get(key, 0)
        if time.time() - last < 3 * 3600:
            return
        self.db.kv_set(key, time.time())
        name = self.svc.display_name(fid, uid)
        await self.svc.hub.send_user(uid, {
            "type": "poke", "family_id": fid, "from": "ai", "kind": "care",
            "chibi": {"from": "ai", "text": f"スマホの電池が残り{level}%だよ。充電してね🔌", "emotion": "worried", "motion": "point", "fx": "sweat",
                      "replies": ["充電したよ"], "reasons": ["見守り設定: 電池が15%以下"]},
        })
        await self.svc.ai_say(fid, f"{name}のスマホ、電池が残り{level}%みたい。充電するよう声をかけておいたよ🔌", emotion="worried",
                              motion="think", reasons=["見守り設定: 電池が15%以下になったらお知らせ", f"{name}本人にも充電のお願いを届けました"])

    # ------------------------------------------------------------------ watching over (見守り)
    async def tick(self) -> None:
        t = timeparse.now_local()
        today = t.date().isoformat()
        for m in self.db.all("SELECT m.*, f.settings AS fsettings FROM members m JOIN families f ON f.id=m.family_id WHERE m.watch=1"):
            fid, uid = m["family_id"], m["user_id"]
            cfg = {**DEFAULT_WATCH, **((m.get("fsettings") or {}).get("watch") if isinstance(m.get("fsettings"), dict) else {})}
            st = self.db.kv_get(f"watch:{fid}:{uid}:{today}", {})
            changed = False
            ch, cm = (int(x) for x in cfg["check_time"].split(":"))
            mins = t.hour * 60 + t.minute
            if not st.get("checked") and ch * 60 + cm <= mins < ch * 60 + cm + 180:  # only in the morning window
                await self.morning_check(fid, uid)
                st["checked"] = time.time()
                changed = True
            if st.get("checked") and not st.get("answered") and not st.get("no_answer_alert") and time.time() - st["checked"] > 90 * 60:
                await self._alert(fid, uid, "朝の「元気？」にまだ返事がないみたい。", ["見守り設定: 毎朝の確認に90分以上返事なし"])
                st["no_answer_alert"] = True
                changed = True
            p = self.get(uid)
            last = p.get("last_active") or 0
            idle_h = (time.time() - last) / 3600 if last else 0
            if (cfg["day_start"] <= t.hour < cfg["day_end"] and last and idle_h >= cfg["alert_hours"]
                    and p.get("state") != "sleep" and not st.get("inactive_alert")):
                await self._alert(fid, uid, f"{int(idle_h)}時間くらいスマホを触っていないみたい。", [f"見守り設定: 日中{cfg['alert_hours']}時間以上操作なし"])
                st["inactive_alert"] = True
                changed = True
            if changed:
                self.db.kv_set(f"watch:{fid}:{uid}:{today}", st)

    async def morning_check(self, fid: str, uid: str) -> None:
        await self.svc.create_asks(fid, None, [uid], "おはよう！今日も元気？", ["元気だよ！", "ちょっと調子わるい"],
                                   context={"kind": "wellness"})

    async def _alert(self, fid: str, uid: str, what: str, reasons: list[str]) -> None:
        name = self.svc.display_name(fid, uid)
        p = self.get(uid)
        last = p.get("last_active")
        when = datetime.fromtimestamp(last, timeparse.TZ).strftime("%H:%M") if last else "不明"
        await self.svc.ai_say(fid, f"{name}、{what}最後にスマホを使ったのは{when}ごろ。電話してみようか？📞", emotion="worried", motion="knock",
                              urgency="high", reasons=reasons + ["カメラや位置情報は使わず、スマホの操作の有無だけで判断しています"],
                              exclude=uid)
        self.timeline(fid, uid, "watch", f"{name}の見守りアラート")

    async def wellness_answer(self, fid: str, uid: str, answer: str) -> None:
        name = self.svc.display_name(fid, uid)
        today = timeparse.now_local().date().isoformat()
        st = self.db.kv_get(f"watch:{fid}:{uid}:{today}", {})
        st["answered"] = answer
        self.db.kv_set(f"watch:{fid}:{uid}:{today}", st)
        await self.report(uid, {"state": "active"})
        good = "元気" in answer and "わる" not in answer
        self.timeline(fid, uid, "wellness", f"{name}：{answer}")
        if good:
            await self.svc.ai_say(fid, f"{name}から朝のお返事：「{answer}」 今日も元気みたい😊", emotion="happy", motion="bothWave",
                                  reasons=["見守り設定: 毎朝本人に「元気？」と確認", "本人の回答をそのまま伝えています"], exclude=uid)
        else:
            await self.svc.ai_say(fid, f"{name}が「{answer}」って。電話してあげて📞", emotion="worried", motion="knock", urgency="high",
                                  reasons=["見守り設定: 毎朝本人に「元気？」と確認", "体調がよくないという回答だったので急ぎで知らせています"], exclude=uid)
