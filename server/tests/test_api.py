import os, tempfile, time, json
os.environ["FAMICHIBI_DATA"] = tempfile.mkdtemp()
from fastapi.testclient import TestClient
from app.main import app, svc, HOME


def auth(tok):
    return {"Authorization": f"Bearer {tok}"}


def recv_until(ws, typ, n=30):
    for _ in range(n):
        ev = json.loads(ws.receive_text())
        if ev["type"] == typ:
            return ev
    raise AssertionError(f"no {typ}")


def test_flow():
    with TestClient(app) as c:
        a = c.post("/api/register", json={"name": "りんたろう"}).json()
        b = c.post("/api/register", json={"name": "こころ"}).json()
        assert c.get("/api/avatars").json()["avatars"], "builtin avatars seeded"
        fam = c.post("/api/families", json={"name": "テスト家", "nickname": "兄"}, headers=auth(a["token"])).json()["family"]
        fid, code = fam["id"], fam["code"]
        assert [ch["name"] for ch in fam["channels"]] == ["リビング", "ちびAI", "おしゃべり部屋"]
        j = c.post("/api/families/join", json={"code": code, "nickname": "妹"}, headers=auth(b["token"])).json()
        assert len(j["family"]["members"]) == 2
        cid = fam["channels"][0]["id"]
        with c.websocket_connect(f"/ws?token={a['token']}") as ws:
            hello = json.loads(ws.receive_text())
            assert hello["type"] == "hello" and hello["families"][0]["id"] == fid
            r = c.post(f"/api/channels/{cid}/messages", json={"text": "今から帰る。迎えに来て！雨やばい☔"}, headers=auth(b["token"])).json()
            ev = recv_until(ws, "message")
            an = ev["message"]["analysis"]
            assert an["urgency"] == "high" and ev["notify"]["mode"] == "urgent"
            assert "今行く！" in an["replies"] and an["reasons"]
            mid = r["message"]["id"]
            assert mid in svc.pending_urgent
            c.post(f"/api/messages/{mid}/ack", headers=auth(a["token"]))
            assert mid not in svc.pending_urgent
        # memo with natural language time
        m = c.post(f"/api/families/{fid}/memos", json={"text": "明日16時：カレー作っておいたから食べてね"}, headers=auth(b["token"])).json()["memo"]
        assert m["text"] == "カレー作っておいたから食べてね" and m["notify_at"] and m["motion"] == "dance"
        # AI rule engine: lights via MCP
        HOME.set("living_light", on=True)
        r = c.post(f"/api/families/{fid}/ai", json={"text": "電気消して"}, headers=auth(a["token"])).json()["message"]
        assert "消しておいた" in r["text"], r
        assert any("MCP" in x for x in r["analysis"]["reasons"])
        assert HOME.devices["living_light"]["on"] is False
        # schedule with private calendar -> asks
        c.patch(f"/api/families/{fid}/me", json={"calendar_private": True}, headers=auth(b["token"]))
        r = c.post(f"/api/families/{fid}/ai", json={"text": "8月15日の予定、みんな空いてる？"}, headers=auth(a["token"])).json()["message"]
        assert "聞きに行って" in r["text"], r["text"]
        asks = c.get(f"/api/families/{fid}/asks", headers=auth(b["token"])).json()["asks"]
        assert len(asks) == 1
        c.post(f"/api/asks/{asks[0]['id']}/answer", json={"answer": "空いてる"}, headers=auth(b["token"]))
        msgs = c.get(f"/api/channels/{asks[0]['channel_id']}/messages", headers=auth(a["token"])).json()["messages"]
        assert "空いてる" in msgs[-1]["text"] and msgs[-1]["kind"] == "ai"
        # RAG recall
        c.post(f"/api/channels/{cid}/messages", json={"text": "Wi-Fiのパスワードは famichibi2026 だよ"}, headers=auth(b["token"]))
        r = c.post(f"/api/families/{fid}/ai", json={"text": "前にWi-Fiのパスワードなんて言ってた？"}, headers=auth(a["token"])).json()["message"]
        assert "famichibi2026" in r["text"], r["text"]
        # demo seed + scenarios
        d = c.post("/api/demo/seed", headers=auth(a["token"])).json()["family"]
        assert len(d["members"]) == 5
        assert c.post(f"/api/families/{d['id']}/demo/pickup", headers=auth(a["token"])).json()["ok"]
        r = c.post(f"/api/families/{d['id']}/ai", json={"text": "今度の土曜日、みんな空いてる？"}, headers=auth(a["token"])).json()["message"]
        print(r["text"], r["analysis"]["reasons"])
        assert "部活" in r["text"]
        # invite claim (NFC card flow: no account yet)
        inv = c.post(f"/api/families/{fid}/invites", json={"preset_name": "おばあちゃん", "preset_nickname": "祖母"}, headers=auth(a["token"])).json()
        cl = c.post(f"/api/invites/{inv['token']}/claim", json={}).json()
        assert cl["token"] and any(m["nickname"] == "祖母" for m in cl["family"]["members"])
        assert c.post(f"/api/invites/{inv['token']}/claim", json={}).status_code == 410
        # shareable link: an http page that works in any chat app, reusable until it expires
        share = c.post(f"/api/families/{fid}/invites", json={"expires_hours": 24}, headers=auth(a["token"])).json()
        assert share["url"].endswith(f"/join/{share['token']}") and share["expires_at"] and not share["single_use"]
        page = c.get(f"/join/{share['token']}")
        assert page.status_code == 200 and "アプリで参加する" in page.text and "intent://join" in page.text and "まで有効" in page.text
        for name in ("いとこ", "おじ"):
            assert c.post(f"/api/invites/{share['token']}/claim", json={"name": name}).status_code == 200
        assert any(i["token"] == share["token"] and i["uses"] == 2 for i in c.get(f"/api/families/{fid}/invites", headers=auth(a["token"])).json()["invites"])
        old = c.post(f"/api/families/{fid}/invites", json={"expires_hours": 1}, headers=auth(a["token"])).json()
        from app.main import db
        db.update("invites", {"token": old["token"]}, {"expires_at": 1.0})
        assert c.post(f"/api/invites/{old['token']}/claim", json={}).status_code == 410
        assert c.get(f"/join/{old['token']}").status_code == 410 and "期限切れ" in c.get(f"/join/{old['token']}").text
        assert c.delete(f"/api/families/{fid}/invites/{share['token']}", headers=auth(a["token"])).json()["ok"]
        assert c.get(f"/join/{share['token']}").status_code == 404


def test_presence_and_watch():
    with TestClient(app) as c:
        a = c.post("/api/register", json={"name": "兄"}).json()
        H = auth(a["token"])
        fam = c.post("/api/demo/seed", headers=H).json()["family"]
        fid = fam["id"]
        by = {m["nickname"]: m for m in fam["members"]}
        assert by["祖母"]["watch"] and by["父"]["presence"]["at_home"] is False and (by["妹"]["presence"]["label"].startswith("学校") or by["妹"]["presence"]["label"] == "移動中")
        assert len({m["avatar"] for m in fam["members"]} | {fam["ai_avatar"]}) == 6, "everyone looks different"
        with c.websocket_connect(f"/ws?token={a['token']}") as ws:
            json.loads(ws.receive_text())
            c.post(f"/api/families/{fid}/demo/arrive", headers=H)
            ev = recv_until(ws, "arrive")
            assert ev["chibi"]["text"] == "ただいま！" and ev["user_id"] == by["妹"]["user_id"]
            c.post(f"/api/families/{fid}/demo/wellness", headers=H)
            m = None
            for _ in range(40):
                e = json.loads(ws.receive_text())
                if e["type"] == "message" and "朝のお返事" in e["message"]["text"]:
                    m = e["message"]; break
            assert m and "元気" in m["text"]
            c.post(f"/api/families/{fid}/demo/unwell", headers=H)
            e = recv_until(ws, "message")
            assert e["message"]["analysis"]["urgency"] == "high" and e["notify"]["mode"] == "urgent"
        tl = c.get(f"/api/families/{fid}/timeline", headers=H).json()["timeline"]
        assert any(t["kind"] == "arrive" for t in tl)
        assert c.get("/api/info").json()["lan_urls"] is not None


def test_idempotent_retries_and_client_logs():
    """An app retrying a queued write (same Idempotency-Key) must not create duplicates."""
    with TestClient(app) as c:
        a = c.post("/api/register", json={"name": "母"}).json()
        h = {**auth(a["token"]), "Idempotency-Key": "op-123"}
        fid = c.post("/api/families", json={"name": "テスト家"}, headers=auth(a["token"])).json()["family"]["id"]
        r1 = c.post(f"/api/families/{fid}/tasks", json={"title": "お風呂掃除"}, headers=h)
        r2 = c.post(f"/api/families/{fid}/tasks", json={"title": "お風呂掃除"}, headers=h)
        assert r1.status_code == r2.status_code == 200 and r1.json()["task"]["id"] == r2.json()["task"]["id"]
        assert r2.headers.get("idempotent-replay") == "true"
        tasks = c.get(f"/api/families/{fid}/tasks", headers=auth(a["token"])).json()["tasks"]
        assert [t["title"] for t in tasks] == ["お風呂掃除"]
        # a different key is a different operation
        c.post(f"/api/families/{fid}/tasks", json={"title": "お風呂掃除"}, headers={**auth(a["token"]), "Idempotency-Key": "op-124"})
        assert len(c.get(f"/api/families/{fid}/tasks", headers=auth(a["token"])).json()["tasks"]) == 2
        # crash logs from the app
        assert c.post("/api/client-logs", json={"text": "java.lang.IllegalArgumentException: Key was already used", "kind": "crash"},
                      headers=auth(a["token"])).json()["ok"]
