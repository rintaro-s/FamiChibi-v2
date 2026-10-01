"""AI chibi: a family helper agent.

Two engines, same tools:
  * LLM engine  - Ollama chat API with tool calling (default model: Qwen3.5 9B, configurable per family)
  * Rule engine - deterministic intent rules; used when no LLM is reachable (offline demo) or it fails

Design rules (from the proposal): the AI never speaks *for* a family member. If it does not know
something about a person (e.g. a private calendar), it asks that person (ask_members) and only
reports their own answer. Every reply carries `reasons`: the tools used and sources referenced,
shown in the app as 「なぜ？」 so users can judge how trustworthy the answer is.
"""
from __future__ import annotations

import json
import logging
import re
import time
from dataclasses import dataclass, field
from datetime import date, datetime, timedelta

import httpx

from . import analyzer, timeparse
from .db import new_id, now
from .mcp_hub import MCPSession
from .services import AI_ID, Services

log = logging.getLogger("famichibi.agent")

DEFAULT_OLLAMA = "http://127.0.0.1:11434"
DEFAULT_MODEL = "qwen3.5:9b"


@dataclass
class Run:
    fid: str
    uid: str
    cid: str
    text: str
    reasons: list[str] = field(default_factory=list)
    actions: list[dict] = field(default_factory=list)
    citations: list[dict] = field(default_factory=list)
    engine: str = "rule"
    emotion: str | None = None
    motion: str | None = None
    fx: str | None = None
    asked: bool = False


class Agent:
    def __init__(self, svc: Services):
        self.svc = svc
        self.db = svc.db
        self._health: dict[str, tuple[float, bool]] = {}

    # ------------------------------------------------------------------ entry
    def is_addressed(self, fam: dict, text: str, channel: dict) -> bool:
        if not fam["ai_enabled"]:
            return False
        if channel.get("name") in ("ちびAI", fam["ai_name"]):
            return True
        t = text.strip()
        names = {fam["ai_name"], "ちびAI", "AIちびっこ", "ちびっこAI", "AI"}
        return any(t.startswith(n) or t.startswith("@" + n) for n in names)

    def strip_address(self, fam: dict, text: str) -> str:
        t = text.strip()
        for n in sorted({fam["ai_name"], "ちびAI", "AIちびっこ", "ちびっこAI", "AI"}, key=len, reverse=True):
            for p in ("@" + n, n):
                if t.startswith(p):
                    return t[len(p):].lstrip(" 、,:：さんちゃん").strip() or t
        return t

    async def handle(self, fid: str, uid: str, text: str, cid: str | None = None) -> dict:
        fam = self.db.family(fid)
        cid = cid or self.svc.default_channel(fid)
        run = Run(fid=fid, uid=uid, cid=cid, text=self.strip_address(fam, text))
        await self.svc.broadcast_family(fid, {"type": "typing", "channel_id": cid, "user_id": AI_ID})
        settings = fam["settings"] or {}
        answer = None
        mcp_cfg = settings.get("mcp_servers") if fam["iot_enabled"] else []
        # Hybrid: clear intents (home control, schedules, asking someone, reminders, history search)
        # always go through the deterministic rules -> the same input gives the same, explainable
        # action. The LLM only handles what the rules cannot classify (free conversation etc.).
        async with MCPSession(mcp_cfg) as mcp:
            llm_ok = settings.get("llm_enabled", True) and await self.llm_available(settings)
            answer = await self.rule_run(run, fam, mcp, defer_to_llm=llm_ok)
            if answer is None:
                try:
                    answer = await self.llm_run(run, fam, mcp)
                    run.engine = "llm"
                except Exception as e:
                    log.warning("LLM failed, falling back to rules: %s", e)
                    run.reasons.append(f"LLMエラーのためルールエンジンに切り替え（{type(e).__name__}）")
                    answer = await self.rule_run(run, fam, mcp)
        emo = run.emotion or analyzer.analyze(answer).emotion
        if emo == "neutral":
            emo = "happy"
        engine = "LLM（" + (settings.get("model") or DEFAULT_MODEL) + "）・自由会話" if run.engine == "llm" else "ルールエンジン（確定的な処理）"
        run.reasons.insert(0, f"判断: {engine}")
        return await self.svc.ai_say(
            fid, answer, cid=cid, reasons=run.reasons, emotion=emo, motion=run.motion, fx=run.fx,
            extra={"engine": run.engine, "actions": run.actions, "citations": run.citations, "request_by": uid},
        )

    # ------------------------------------------------------------------ tools (shared by both engines)
    def member_by_label(self, fid: str, label: str) -> dict | None:
        label = (label or "").strip().lstrip("@")
        label = re.sub(r"(さん|ちゃん|くん|君)$", "", label)
        if not label:
            return None
        for m in self.db.members(fid):
            for n in (m["nickname"], m["name"]):
                if n and (n == label or n in label or label in n):
                    return m
        aliases = {"お父さん": "父", "パパ": "父", "おとうさん": "父", "お母さん": "母", "ママ": "母", "おかあさん": "母",
                   "お兄ちゃん": "兄", "兄ちゃん": "兄", "お姉ちゃん": "姉", "姉ちゃん": "姉", "おばあちゃん": "祖母", "おじいちゃん": "祖父"}
        alt = aliases.get(label)
        rev = {v: k for k, v in aliases.items()}
        for m in self.db.members(fid):
            for n in (m["nickname"], m["name"]):
                if n and (alt and (n == alt or alt in n) or rev.get(n) == label):
                    return m
        return None

    def t_search_history(self, run: Run, query: str) -> list[dict]:
        rows = self.db.search_messages(run.fid, query, limit=12)
        # Only family members' own words count as evidence: skip AI replies and questions asked to the AI.
        ai_ch = {c["id"] for c in self.db.channels(run.fid) if c["name"] == "ちびAI"}
        rows = [r for r in rows if r["kind"] != "ai" and not (r.get("meta") or {}).get("to_ai") and r["channel_id"] not in ai_ch
                and r["text"].strip() != run.text.strip()][:6]
        out = []
        for r in rows:
            who = self.svc.display_name(run.fid, r["user_id"])
            when = timeparse.fmt(r["created_at"])
            out.append({"id": r["id"], "who": who, "when": when, "text": r["text"][:120]})
        run.citations.extend(out[:3])
        run.reasons.append(f"🔎 過去のチャットを検索「{query}」→ {len(out)}件")
        for c in out[:3]:
            run.reasons.append(f"📄 {c['when']} {c['who']}「{c['text'][:40]}」")
        return out

    def t_get_schedule(self, run: Run, day: date) -> dict:
        a, b = timeparse.day_bounds(day)
        result = {"date": day.isoformat(), "members": []}
        for m in self.db.members(run.fid):
            evs = self.db.all(
                "SELECT * FROM events WHERE family_id=? AND user_id=? AND start < ? AND COALESCE(end, start + 3600) > ? ORDER BY start",
                (run.fid, m["user_id"], b, a),
            )
            label = m["nickname"] or m["name"]
            if m["calendar_private"]:
                result["members"].append({"user_id": m["user_id"], "name": label, "status": "private"})
            else:
                result["members"].append({
                    "user_id": m["user_id"], "name": label, "status": "busy" if evs else "free",
                    "events": [{"title": e["title"] if not e["private"] else "予定あり", "at": timeparse.fmt(e["start"])} for e in evs],
                })
        shared = self.db.all("SELECT * FROM events WHERE family_id=? AND user_id IS NULL AND start < ? AND COALESCE(end, start+3600) > ?", (run.fid, b, a))
        result["family_events"] = [{"title": e["title"], "at": timeparse.fmt(e["start"])} for e in shared]
        pv = sum(1 for m in result["members"] if m["status"] == "private")
        run.reasons.append(f"📅 {day.month}/{day.day}のカレンダーを確認（公開{len(result['members']) - pv}人・非公開{pv}人）")
        return result

    async def t_ask_members(self, run: Run, user_ids: list[str], question: str, options: list[str], context: dict | None = None) -> str:
        user_ids = [u for u in user_ids if u and u != run.uid]
        if not user_ids:
            return "no one to ask"
        await self.svc.create_asks(run.fid, run.uid, user_ids, question, options, cid=run.cid, context=context)
        names = "・".join(self.svc.display_name(run.fid, u) for u in user_ids)
        run.reasons.append(f"🙋 {names} に直接確認中（本人の回答だけを伝えます）")
        run.actions.append({"type": "ask", "targets": user_ids, "question": question})
        run.asked = True
        return f"asked {names}"

    async def t_create_memo(self, run: Run, text: str, when: str = "", to: str = "") -> dict:
        target = self.member_by_label(run.fid, to) if to else None
        p = timeparse.parse(when or text)
        memo = await self.svc.create_memo(run.fid, run.uid, p.text if not when else text, notify_at=p.ts,
                                          target=target["user_id"] if target else None, auto_time=False)
        run.actions.append({"type": "memo", "id": memo["id"]})
        run.reasons.append(f"📝 冷蔵庫メモを作成（通知: {memo['notify_label'] or 'なし'}{'・' + (target['nickname'] or target['name']) + '宛' if target else ''}）")
        return memo

    async def t_add_event(self, run: Run, title: str, start: float, end: float | None = None, member: str = "") -> dict:
        m = self.member_by_label(run.fid, member) if member else None
        ev = {"id": new_id("ev_"), "family_id": run.fid, "user_id": m["user_id"] if m else None, "title": title, "start": start,
              "end": end, "all_day": 0, "private": 0, "note": "", "created_at": now()}
        self.db.insert("events", ev)
        await self.svc.broadcast_family(run.fid, {"type": "event", "event": ev})
        run.actions.append({"type": "event", "id": ev["id"]})
        run.reasons.append(f"📅 予定を追加「{title}」{timeparse.fmt(start)}")
        return ev

    async def t_add_task(self, run: Run, title: str, assignee: str = "") -> dict:
        m = self.member_by_label(run.fid, assignee) if assignee else None
        task = {"id": new_id("tk_"), "family_id": run.fid, "title": title, "assignee_id": m["user_id"] if m else None, "done": 0,
                "due": None, "created_by": run.uid, "created_at": now()}
        self.db.insert("tasks", task)
        await self.svc.broadcast_family(run.fid, {"type": "task", "task": task})
        run.actions.append({"type": "task", "id": task["id"]})
        run.reasons.append(f"✅ やることリストに追加「{title}」")
        return task

    async def t_mcp(self, run: Run, mcp: MCPSession, qualified: str, args: dict) -> str:
        ok, out = await mcp.call(qualified, args)
        argstr = ", ".join(f"{k}={v}" for k, v in args.items())
        run.reasons.append(f"🔧 MCP {qualified.replace('__', '.')}({argstr}) → {'OK' if ok else '失敗'}")
        run.actions.append({"type": "mcp", "tool": qualified, "args": args, "ok": ok})
        return out

    def digest(self, run: Run, hours: int = 24) -> str:
        since = time.time() - hours * 3600
        rows = self.db.all(
            """SELECT m.* FROM messages m LEFT JOIN reads r ON r.channel_id=m.channel_id AND r.user_id=?
               WHERE m.family_id=? AND m.created_at>=? AND m.kind IN ('text','voice','memo') AND COALESCE(m.user_id,'')!=?
               AND m.id > COALESCE(r.last_id, 0) ORDER BY m.id""",
            (run.uid, run.fid, since, run.uid),
        )
        if not rows:
            rows = self.db.all(
                "SELECT * FROM messages WHERE family_id=? AND created_at>=? AND kind IN ('text','voice') AND COALESCE(user_id,'')!=? ORDER BY id DESC LIMIT 8",
                (run.fid, since, run.uid))[::-1]
            if not rows:
                return "最近のやりとりは特にないよ。平和だね☺"
        by: dict[str, list[str]] = {}
        important = []
        for r in rows:
            who = self.svc.display_name(run.fid, r["user_id"])
            by.setdefault(who, []).append(r["text"] if r["kind"] != "voice" else "🎤ボイス")
            if (r["analysis"] or {}).get("urgency") == "high" or timeparse.parse(r["text"]).when:
                important.append(f"{who}「{r['text'][:30]}」")
        lines = [f"・{who}：{len(t)}件（{t[-1][:24]}）" for who, t in by.items()]
        run.reasons.append(f"🗂 未読 {len(rows)}件を送信者ごとに整理")
        text = "見逃したかもしれない連絡をまとめたよ！\n" + "\n".join(lines)
        if important:
            text += "\n⚠ 大事そう：" + " / ".join(important[:3])
        return text

    # ------------------------------------------------------------------ rule engine
    async def rule_run(self, run: Run, fam: dict, mcp: MCPSession, defer_to_llm: bool = False) -> str | None:
        t = run.text
        n = t.replace(" ", "").replace("　", "")

        # --- smart home ------------------------------------------------------------
        if fam["iot_enabled"] and mcp.tools:
            r = await self._rule_home(run, n, mcp)
            if r:
                return r
        elif re.search(r"(電気|照明|ライト|エアコン|カーテン|鍵)", n):
            run.reasons.append("IoT連携がこの家族サーバーでOFFになっています")
            return "おうちの家電操作はOFFになってるよ。家族サーバー設定の「IoT連携」をONにしてね！"

        # --- schedule availability ------------------------------------------------------
        if re.search(r"予定|空いて|あいて|ひま|暇|都合", n):
            p = timeparse.parse(t)
            day = p.when.date() if p.when else (timeparse.now_local().date() + timedelta(days=1) if "明日" in n else None)
            if day:
                sched = self.t_get_schedule(run, day)
                free = [m["name"] for m in sched["members"] if m["status"] == "free" and m["user_id"] != run.uid]
                busy = [f"{m['name']}（{m['events'][0]['title']}）" for m in sched["members"] if m["status"] == "busy" and m["user_id"] != run.uid]
                private = [m for m in sched["members"] if m["status"] == "private" and m["user_id"] != run.uid]
                label = f"{day.month}/{day.day}"
                parts = []
                if free:
                    parts.append(f"空いてる：{'・'.join(free)}")
                if busy:
                    parts.append(f"予定あり：{'・'.join(busy)}")
                known = "（カレンダーより）" + " / ".join(parts) if parts else ""
                if private:
                    await self.t_ask_members(run, [m["user_id"] for m in private], f"{label}って空いてる？", ["空いてる", "予定ある"],
                                             context={"summary_head": f"{label}の予定、本人に聞いてきたよ！", "known": known})
                    run.motion = "run"
                    names = "・".join(m["name"] for m in private)
                    return f"{label}の予定を見てきたよ！\n" + ("\n".join(parts) + "\n" if parts else "") + f"{names}はカレンダーが鍵付きだから、直接聞きに行ってくるね🏃"
                run.emotion = "happy"
                return f"{label}の予定を見てきたよ！\n" + ("\n".join(parts) if parts else "みんな予定は入ってないみたい！")

        # --- ask someone on my behalf -------------------------------------------------
        m = re.search(r"(.+?)(に|へ)(.+?)(って|と)?(聞いて|きいて|聞いてきて|確認して)", t)
        if m:
            target = self.member_by_label(run.fid, m.group(1))
            if target:
                q = m.group(3).strip("「」『』、 ")
                if not q.endswith(("?", "？")):
                    q += "？"
                await self.t_ask_members(run, [target["user_id"]], q, ["うん", "ううん", "あとで返事する"],
                                         context={"summary_head": f"{target['nickname'] or target['name']}に聞いてきたよ！"})
                run.motion = "run"
                return f"りょうかい！{target['nickname'] or target['name']}に「{q}」って聞いてくるね🏃"

        # --- reminders / memos -------------------------------------------------------------
        if re.search(r"(リマインド|覚えておいて|覚えといて|メモして|メモっといて|知らせて|教えて|伝えて)", n):
            p = timeparse.parse(t)
            tm = re.search(r"(.+?)(に|へ)(.+?)(って|と)(伝えて|知らせて)", t)
            to = ""
            body = p.text
            if tm and self.member_by_label(run.fid, tm.group(1)):
                to = tm.group(1)
                body = timeparse.parse(tm.group(3)).text or tm.group(3)
            body = re.sub(r"(って|と)?(リマインド|覚えておいて|覚えといて|メモして|メモっといて|知らせて|教えて|伝えて)(ね|ください|くれる\?|くれる？)?$", "", body).strip("、 ")
            if p.when or to:
                memo = await self.t_create_memo(run, body or t, when=t if p.when else "", to=to)
                when = memo["notify_label"] or "今すぐ"
                return f"まかせて！{when}に{('、' + to + 'に') if to else ''}「{memo['text']}」ってお知らせするね📝"

        # --- tasks -----------------------------------------------------------------------
        tm = re.search(r"(?:やること|タスク|買い物リスト|TODO)に(.+?)(を)?(追加|入れて|いれて)", t)
        if tm:
            task = await self.t_add_task(run, tm.group(1).strip("「」"))
            return f"「{task['title']}」をやることリストに入れたよ✅"

        # --- digest ----------------------------------------------------------------------
        if re.search(r"(まとめ|要約|何があった|なにがあった|見逃|未読)", n):
            run.motion = "talk"
            return self.digest(run)

        # --- history (RAG) ----------------------------------------------------------------
        if re.search(r"(前に|この前|このまえ|こないだ|昔|言ってた|いってた|覚えてる|なんだっけ|何だっけ|いつだっけ)", n):
            q = re.sub(r"(ねえ|ねぇ|前に|この前|このまえ|こないだ|って|何て|なんて|言ってた|いってた|覚えてる|なんだっけ|何だっけ|いつだっけ|誰か|だれか|けど|[?？。、!！])", " ", t)
            q = re.sub(r"(の|は|が|を|に|で|と|も)(?=\s|$)", " ", q)
            q = " ".join(w for w in q.split() if len(w) >= 2)
            hits = self.t_search_history(run, q) if q else []
            if hits:
                h = hits[0]
                more = f"（ほかに{len(hits) - 1}件）" if len(hits) > 1 else ""
                run.motion = "point"
                return f"見つけたよ！{h['when']}に{h['who']}が「{h['text']}」って言ってたよ{more}"
            return "ごめん、過去のチャットからは見つけられなかった…🙏 キーワードを変えて聞いてみて！"

        # --- capabilities / chit-chat ----------------------------------------------------------
        if re.search(r"(何ができる|なにができる|使い方|ヘルプ|help)", n):
            return ("できること：\n・「電気消して」→ おうちの家電を操作\n・「8月15日みんな空いてる？」→ 予定確認（鍵付きの人には聞きに行く）\n"
                    "・「パパに何時に帰るか聞いて」→ 代わりに質問\n・「明日7時にゴミ出しってリマインドして」→ メモ通知\n"
                    "・「前にWi-Fiのパスワードなんて言ってた？」→ 過去のチャット検索\n・「まとめて」→ 見逃した連絡の整理")
        if defer_to_llm:
            return None  # free conversation: let the LLM answer
        if re.search(r"(ありがと|サンキュ|助かった)", n):
            run.motion = "bow"
            return "どういたしまして！またいつでも呼んでね☺"
        if re.search(r"(おはよ|こんにちは|こんばんは|やあ|ハロー)", n):
            run.motion = "wave"
            return "やっほー！今日もみんなの連絡、ちゃんと届けるね！"
        if re.search(r"(つかれた|疲れた|しんどい)", n):
            run.motion = "pray"
            run.emotion = "sad"
            return "おつかれさま…！無理しないでね。家族のみんなにも「おつかれ」って伝えようか？"
        run.motion = "think"
        run.reasons.append("意図を特定できませんでした")
        return "うーん、ちょっとわからなかった…🙏「何ができる？」って聞いてくれたら、できることを教えるよ！"

    async def _rule_home(self, run: Run, n: str, mcp: MCPSession) -> str | None:
        off = re.search(r"(消し|消して|けして|オフ|off|OFF|切って|きって|閉め|しめて|かけて|施錠|ロック)", n)
        on = re.search(r"(つけ|点け|オン|on|ON|入れて|いれて|開け|あけて|解錠)", n)
        room = next((r for r in ("リビング", "キッチン", "寝室", "玄関", "全部", "すべて", "家中") if r in n), "")
        light_tool = mcp.find_by_name("set_light")
        list_tool = mcp.find_by_name("list_devices")
        if re.search(r"(電気|照明|ライト|明かり|あかり)", n) and (off or on) and light_tool:
            target = "all" if room in ("全部", "すべて", "家中") or not room else room
            want = bool(on) and not off
            run.reasons.append(f"「{run.text}」を照明{'点灯' if want else '消灯'}の依頼と判断")
            if list_tool:
                states = json.loads(await self.t_mcp(run, mcp, list_tool.qualified, {}) or "{}")
                lights = {k: v for k, v in states.items() if v.get("type") == "light" and (target == "all" or target in v.get("room", ""))}
                need = [v["name"] for v in lights.values() if v.get("on") != want]
                if lights and not need:
                    run.motion = "nod"
                    return f"{'もう全部ついてる' if want else 'もう全部消えてる'}よ！確認しておいたよ💡"
            res = json.loads(await self.t_mcp(run, mcp, light_tool.qualified, {"target": target, "on": want}) or "{}")
            changed = [c["name"] for c in res.get("changed", []) if c.get("before") != c.get("after")]
            run.fx = "light"
            run.motion = "point"
            if not res.get("ok"):
                return f"ごめん、{target}の電気が見つからなかった…"
            return f"{'・'.join(changed) or '電気'}を{'つけた' if want else '消しておいた'}よ！💡"
        if re.search(r"(エアコン|冷房|暖房|クーラー)", n):
            tool = mcp.find_by_name("set_climate")
            if tool:
                want = not off
                mode = "heat" if "暖房" in n else "cool" if ("冷房" in n or "クーラー" in n) else ""
                temp = re.search(r"(\d{2})度", n)
                args = {"on": want}
                if mode:
                    args["mode"] = mode
                if temp:
                    args["target_temperature"] = float(temp.group(1))
                run.reasons.append("エアコン操作の依頼と判断")
                await self.t_mcp(run, mcp, tool.qualified, args)
                run.motion = "point"
                return f"エアコンを{'つけた' if want else '止めた'}よ！" + (f"{temp.group(1)}度にしておいたね" if temp else "")
        if "カーテン" in n:
            tool = mcp.find_by_name("set_cover")
            if tool:
                want_open = bool(re.search(r"(開け|あけ)", n))
                await self.t_mcp(run, mcp, tool.qualified, {"open": want_open})
                return f"カーテンを{'開けた' if want_open else '閉めた'}よ！"
        if re.search(r"(鍵|カギ|かぎ)", n):
            tool = mcp.find_by_name("set_lock")
            if tool and (off or on or "閉め" in n or "かけ" in n):
                lock = bool(re.search(r"(閉め|しめ|かけ|施錠|ロック)", n))
                await self.t_mcp(run, mcp, tool.qualified, {"locked": lock})
                return f"玄関の鍵を{'かけた' if lock else '開けた'}よ🔑"
        if re.search(r"(温度|湿度|暑い|寒い|洗濯)", n):
            tool = mcp.find_by_name("get_sensor")
            if tool:
                key = "洗濯機" if "洗濯" in n else "湿度" if "湿度" in n else "温度"
                data = json.loads(await self.t_mcp(run, mcp, tool.qualified, {"target": key}) or "{}")
                vals = [f"{v['name']}は{v['value']}{v.get('unit', '')}" for v in data.values() if isinstance(v, dict) and "value" in v]
                if vals:
                    extra = "（エアコンつけようか？）" if "暑" in n else ""
                    return "・".join(vals) + "だよ！" + extra
        return None

    # ------------------------------------------------------------------ LLM engine
    async def llm_available(self, settings: dict) -> bool:
        url = settings.get("ollama_url") or DEFAULT_OLLAMA
        model = settings.get("model") or DEFAULT_MODEL
        key = f"{url}|{model}"
        cached = self._health.get(key)
        if cached and time.time() - cached[0] < 30:
            return cached[1]
        ok = False
        try:
            async with httpx.AsyncClient(timeout=2.5) as c:
                r = await c.get(f"{url}/api/tags")
                names = {m.get("name") for m in r.json().get("models", [])} | {m.get("model") for m in r.json().get("models", [])}
                ok = model in names or f"{model}:latest" in names
        except Exception:
            ok = False
        self._health[key] = (time.time(), ok)
        return ok

    def _tool_specs(self, mcp: MCPSession) -> list[dict]:
        def fn(name, desc, props, req):
            return {"type": "function", "function": {"name": name, "description": desc,
                                                      "parameters": {"type": "object", "properties": props, "required": req}}}
        specs = [
            fn("search_history", "家族の過去のチャットを全文検索する（RAG）。過去の発言の確認に使う。", {"query": {"type": "string"}}, ["query"]),
            fn("get_schedule", "指定日の家族全員の予定と空き状況を取得する。非公開カレンダーの人は status=private。", {"date": {"type": "string", "description": "YYYY-MM-DD"}}, ["date"]),
            fn("ask_members", "家族に直接質問を届ける（非公開カレンダーの人や本人しか知らないこと）。回答は後で自動で集計される。",
               {"members": {"type": "array", "items": {"type": "string"}, "description": "呼び名（例: 父, 妹）"},
                "question": {"type": "string"}, "options": {"type": "array", "items": {"type": "string"}}}, ["members", "question"]),
            fn("create_memo", "冷蔵庫メモ／リマインダーを作る。when は自然文（例: 明日16時）。to は宛先の呼び名（省略で全員）。",
               {"text": {"type": "string"}, "when": {"type": "string"}, "to": {"type": "string"}}, ["text"]),
            fn("add_event", "家族カレンダーに予定を追加する。", {"title": {"type": "string"}, "start": {"type": "string", "description": "YYYY-MM-DD HH:MM"},
                                                     "member": {"type": "string"}}, ["title", "start"]),
            fn("add_task", "家族のやることリストに追加する。", {"title": {"type": "string"}, "assignee": {"type": "string"}}, ["title"]),
            fn("digest", "ユーザーが見逃した連絡をまとめる。", {}, []),
        ]
        for t in mcp.tools:
            specs.append({"type": "function", "function": {"name": t.qualified, "description": f"[MCP:{t.server}] {t.description}", "parameters": t.schema}})
        return specs

    async def llm_run(self, run: Run, fam: dict, mcp: MCPSession) -> str:
        settings = fam["settings"] or {}
        url = settings.get("ollama_url") or DEFAULT_OLLAMA
        model = settings.get("model") or DEFAULT_MODEL
        members = self.db.members(run.fid)
        roster = "、".join(f"{m['nickname'] or m['name']}" + ("(カレンダー非公開)" if m["calendar_private"] else "") for m in members)
        me = self.svc.display_name(run.fid, run.uid)
        today = timeparse.now_local()
        system = (
            f"あなたは家族向けSNS「FamiChibi」のAIちびっこ「{fam['ai_name']}」です。家族のグループに住んでいて、生活を手伝います。\n"
            f"今日は{today:%Y-%m-%d (%a) %H:%M}。家族: {roster}。話しかけているのは「{me}」。\n"
            "ルール:\n- 家族本人の発言や気持ちを推測で代弁しない。本人しか知らないことは ask_members で本人に聞く。\n"
            "- 予定の確認は get_schedule。非公開の人には ask_members で聞く。\n- 家電操作はまず状態を確認し、必要なときだけ操作する。\n"
            "- 過去の話題は search_history で調べ、見つかった発言を根拠にする。\n"
            "- 返答は日本語で2〜3文、やさしく親しみやすい口調。絵文字は1つまで。"
        )
        history = self.db.all("SELECT * FROM messages WHERE channel_id=? ORDER BY id DESC LIMIT 6", (run.cid,))[::-1]
        msgs = [{"role": "system", "content": system}]
        for h in history[:-1]:
            role = "assistant" if h["kind"] == "ai" else "user"
            msgs.append({"role": role, "content": (f"{self.svc.display_name(run.fid, h['user_id'])}: " if role == "user" else "") + h["text"]})
        msgs.append({"role": "user", "content": f"{me}: {run.text}"})
        tools = self._tool_specs(mcp)
        async with httpx.AsyncClient(timeout=90) as c:
            for _ in range(5):
                r = await c.post(f"{url}/api/chat", json={"model": model, "messages": msgs, "tools": tools, "stream": False, "think": False,
                                                          "options": {"temperature": 0.3}})
                r.raise_for_status()
                msg = r.json()["message"]
                calls = msg.get("tool_calls") or []
                if not calls:
                    content = re.sub(r"<think>.*?</think>", "", msg.get("content") or "", flags=re.S).strip()
                    # small models sometimes echo the chat format ("兄: ...", "「ちびAI」：...")
                    content = re.sub(r"^「?[^」:：\n]{1,12}」?\s*[:：]\s*", "", content)
                    return content or "うん、わかった！"
                msgs.append(msg)
                for call in calls:
                    name = call["function"]["name"]
                    args = call["function"].get("arguments") or {}
                    if isinstance(args, str):
                        try:
                            args = json.loads(args)
                        except ValueError:
                            args = {}
                    try:
                        result = await self._dispatch(run, mcp, name, args)
                    except Exception as e:
                        result = f"error: {e}"
                    msgs.append({"role": "tool", "content": result if isinstance(result, str) else json.dumps(result, ensure_ascii=False)[:4000]})
        return "ちょっと考えすぎちゃった…もう一度お願い🙏"

    async def _dispatch(self, run: Run, mcp: MCPSession, name: str, args: dict):
        if name == "search_history":
            return self.t_search_history(run, args.get("query", ""))
        if name == "get_schedule":
            try:
                d = date.fromisoformat(args.get("date", "")[:10])
            except ValueError:
                p = timeparse.parse(args.get("date", ""))
                d = p.when.date() if p.when else timeparse.now_local().date()
            return self.t_get_schedule(run, d)
        if name == "ask_members":
            ids = []
            for label in args.get("members") or []:
                m = self.member_by_label(run.fid, label)
                if m:
                    ids.append(m["user_id"])
            return await self.t_ask_members(run, ids, args.get("question", ""), args.get("options") or ["うん", "ううん"])
        if name == "create_memo":
            memo = await self.t_create_memo(run, args.get("text", ""), args.get("when", ""), args.get("to", ""))
            return {"ok": True, "notify": memo["notify_label"]}
        if name == "add_event":
            try:
                start = datetime.fromisoformat(args["start"]).replace(tzinfo=timeparse.TZ).timestamp()
            except (KeyError, ValueError):
                p = timeparse.parse(args.get("start", ""))
                start = p.ts or time.time()
            ev = await self.t_add_event(run, args.get("title", "予定"), start, member=args.get("member", ""))
            return {"ok": True, "id": ev["id"]}
        if name == "add_task":
            t = await self.t_add_task(run, args.get("title", ""), args.get("assignee", ""))
            return {"ok": True, "id": t["id"]}
        if name == "digest":
            return self.digest(run)
        if "__" in name:
            return await self.t_mcp(run, mcp, name, args)
        return f"unknown tool {name}"
