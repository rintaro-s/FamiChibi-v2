"""WebSocket fan-out: user -> set of live connections (phone, web, ...)."""
from __future__ import annotations

import asyncio
import json
import logging
from collections import defaultdict

from fastapi import WebSocket

log = logging.getLogger("famichibi.hub")


class Hub:
    def __init__(self) -> None:
        self.conns: dict[str, set[WebSocket]] = defaultdict(set)
        self.voice: dict[str, set[str]] = defaultdict(set)  # channel_id -> user ids in the voice room
        self.presence: dict[str, float] = {}  # user_id -> last active time

    def add(self, uid: str, ws: WebSocket) -> None:
        self.conns[uid].add(ws)

    def remove(self, uid: str, ws: WebSocket) -> None:
        self.conns[uid].discard(ws)
        if not self.conns[uid]:
            del self.conns[uid]

    def online(self, uid: str) -> bool:
        return bool(self.conns.get(uid))

    async def send_user(self, uid: str, event: dict) -> int:
        conns = list(self.conns.get(uid, ()))
        if not conns:
            return 0
        data = json.dumps(event, ensure_ascii=False)
        sent = 0
        for ws in conns:
            try:
                await asyncio.wait_for(ws.send_text(data), timeout=5)
                sent += 1
            except Exception as e:
                log.debug("drop ws for %s: %s", uid, e)
                self.remove(uid, ws)
        return sent

    async def send_users(self, uids, event: dict) -> None:
        await asyncio.gather(*(self.send_user(u, event) for u in set(uids)))
