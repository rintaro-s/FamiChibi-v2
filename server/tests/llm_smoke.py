"""Manual smoke test of the LLM engine (needs a running Ollama). Usage: python tests/llm_smoke.py [model]"""
import os, sys, tempfile, time
os.environ["FAMICHIBI_DATA"] = tempfile.mkdtemp()
from fastapi.testclient import TestClient
from app.main import app, HOME

model = sys.argv[1] if len(sys.argv) > 1 else "qwen3.5:9b"
with TestClient(app) as c:
    a = c.post("/api/register", json={"name": "兄"}).json()
    H = {"Authorization": f"Bearer {a['token']}"}
    fam = c.post("/api/demo/seed", headers=H).json()["family"]
    c.patch(f"/api/families/{fam['id']}", json={"settings": {"model": model}}, headers=H)
    print(c.get(f"/api/families/{fam['id']}/ai/status", headers=H).json()["engine"])
    HOME.set("living_light", on=True)
    for q in ["リビングの電気消して", "前にWi-Fiのパスワードなんて言ってた？", "今度の土曜日、みんな空いてる？"]:
        t = time.time()
        r = c.post(f"/api/families/{fam['id']}/ai", json={"text": q}, headers=H).json()["message"]
        print(f"\nQ: {q}  ({time.time()-t:.1f}s)\nA: {r['text']}\n   reasons: {r['analysis']['reasons']}")
    print("\nliving_light on =", HOME.devices["living_light"]["on"])
