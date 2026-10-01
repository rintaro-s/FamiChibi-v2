"""Demo smart home exposed as an MCP server.

The AI chibi talks to smart-home devices only through MCP. In a real home, point the family's MCP
settings at Home Assistant's MCP server (ha-mcp); for the contest demo this module provides a
virtual house with lights, an air conditioner, a curtain and sensors so that "電気消して" works
anywhere. State changes are pushed to all clients (the web UI shows the virtual room).

Run standalone (e.g. for Claude Desktop or any MCP client):  python -m app.home  (stdio)
"""
from __future__ import annotations

import json
import threading
import time
from pathlib import Path
from typing import Callable

from mcp.server.mcpserver import MCPServer

_DEFAULT = {
    "living_light": {"name": "リビングの電気", "type": "light", "room": "リビング", "on": True, "brightness": 80},
    "kitchen_light": {"name": "キッチンの電気", "type": "light", "room": "キッチン", "on": False, "brightness": 100},
    "bedroom_light": {"name": "寝室の電気", "type": "light", "room": "寝室", "on": False, "brightness": 60},
    "entrance_light": {"name": "玄関の電気", "type": "light", "room": "玄関", "on": True, "brightness": 100},
    "living_ac": {"name": "リビングのエアコン", "type": "climate", "room": "リビング", "on": False, "mode": "cool", "target": 26},
    "living_curtain": {"name": "リビングのカーテン", "type": "cover", "room": "リビング", "open": True},
    "entrance_lock": {"name": "玄関の鍵", "type": "lock", "room": "玄関", "locked": False},
    "living_temp": {"name": "リビングの温度", "type": "sensor", "room": "リビング", "value": 27.5, "unit": "℃"},
    "living_humidity": {"name": "リビングの湿度", "type": "sensor", "room": "リビング", "value": 62, "unit": "%"},
    "washer": {"name": "洗濯機", "type": "sensor", "room": "洗面所", "value": "運転終了", "unit": ""},
}


class HomeState:
    def __init__(self, path: Path | None = None):
        self.path = path
        self.lock = threading.Lock()
        self.listeners: list[Callable[[dict], None]] = []
        self.devices: dict = json.loads(json.dumps(_DEFAULT))
        if path and path.exists():
            try:
                self.devices.update(json.loads(path.read_text()))
            except ValueError:
                pass

    def _save(self) -> None:
        if self.path:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self.devices, ensure_ascii=False, indent=1))
        snapshot = self.snapshot()
        for fn in list(self.listeners):
            try:
                fn(snapshot)
            except Exception:
                pass

    def snapshot(self) -> dict:
        with self.lock:
            return {"devices": json.loads(json.dumps(self.devices)), "at": time.time()}

    def find(self, query: str) -> list[str]:
        q = (query or "").strip()
        if q in self.devices:
            return [q]
        hits = [k for k, d in self.devices.items() if q and (q in d["name"] or q in d["room"] or d["name"] in q)]
        return hits

    def set(self, device_id: str, **changes) -> dict:
        with self.lock:
            d = self.devices[device_id]
            d.update({k: v for k, v in changes.items() if v is not None})
            result = dict(d)
        self._save()
        return result


STATE = HomeState()


def build_server(state: HomeState = STATE) -> MCPServer:
    mcp = MCPServer(
        name="famichibi-demo-home",
        instructions="家庭内のデバイス（照明・エアコン・カーテン・鍵・センサー）を確認・操作するMCPサーバー。",
    )

    @mcp.tool()
    def list_devices(room: str = "") -> str:
        """家のデバイス一覧と現在の状態を返す。room を指定するとその部屋だけ。"""
        snap = state.snapshot()["devices"]
        items = {k: v for k, v in snap.items() if not room or room in v["room"]}
        return json.dumps(items, ensure_ascii=False)

    @mcp.tool()
    def set_light(target: str, on: bool, brightness: int | None = None) -> str:
        """照明をつける/消す。target はデバイスIDか部屋名（例: 'リビング', 'all' で全部）。"""
        ids = [k for k, v in state.devices.items() if v["type"] == "light"] if target in ("all", "全部", "すべて") else [
            k for k in state.find(target) if state.devices[k]["type"] == "light"]
        if not ids:
            return json.dumps({"ok": False, "error": f"照明 '{target}' が見つかりません"}, ensure_ascii=False)
        changed = []
        for k in ids:
            before = state.devices[k]["on"]
            state.set(k, on=on, brightness=brightness)
            changed.append({"id": k, "name": state.devices[k]["name"], "before": before, "after": on})
        return json.dumps({"ok": True, "changed": changed}, ensure_ascii=False)

    @mcp.tool()
    def set_climate(on: bool, mode: str = "", target_temperature: float | None = None, target: str = "living_ac") -> str:
        """エアコンを操作する。mode: cool/heat/dry/auto。"""
        ids = [k for k in state.find(target) if state.devices[k]["type"] == "climate"] or ["living_ac"]
        r = state.set(ids[0], on=on, mode=mode or None, target=target_temperature)
        return json.dumps({"ok": True, "device": r}, ensure_ascii=False)

    @mcp.tool()
    def set_cover(open: bool, target: str = "living_curtain") -> str:
        """カーテンを開ける/閉める。"""
        ids = [k for k in state.find(target) if state.devices[k]["type"] == "cover"] or ["living_curtain"]
        r = state.set(ids[0], open=open)
        return json.dumps({"ok": True, "device": r}, ensure_ascii=False)

    @mcp.tool()
    def set_lock(locked: bool, target: str = "entrance_lock") -> str:
        """玄関の鍵を施錠/解錠する。"""
        r = state.set("entrance_lock", locked=locked)
        return json.dumps({"ok": True, "device": r}, ensure_ascii=False)

    @mcp.tool()
    def get_sensor(target: str) -> str:
        """温度・湿度・洗濯機などのセンサー値を取得する。"""
        ids = [k for k in state.find(target) if state.devices[k]["type"] == "sensor"]
        return json.dumps({k: state.devices[k] for k in ids} or {"error": "not found"}, ensure_ascii=False)

    return mcp


if __name__ == "__main__":
    build_server(HomeState(Path(__file__).resolve().parent.parent / "data" / "home.json")).run("stdio")
