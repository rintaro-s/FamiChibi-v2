"""Connects the AI chibi to the MCP servers configured per family.

family.settings["mcp_servers"] = [
  {"name": "home", "type": "builtin"},                                   # virtual demo house (default)
  {"name": "ha",   "type": "http",  "url": "http://homeassistant.local:8086/mcp"},   # e.g. ha-mcp
  {"name": "x",    "type": "stdio", "command": "uvx", "args": ["ha-mcp"], "env": {"HOMEASSISTANT_URL": "..."}}
]

Tools are exposed to the agent as "<server>__<tool>".
"""
from __future__ import annotations

import asyncio
import json
import logging
from contextlib import AsyncExitStack
from dataclasses import dataclass

from mcp import StdioServerParameters
from mcp.client.client import Client

from .home import STATE, build_server

log = logging.getLogger("famichibi.mcp")
DEFAULT_SERVERS = [{"name": "home", "type": "builtin"}]


@dataclass
class ToolInfo:
    server: str
    name: str
    description: str
    schema: dict

    @property
    def qualified(self) -> str:
        return f"{self.server}__{self.name}"


def _target(cfg: dict):
    t = cfg.get("type", "builtin")
    if t == "builtin":
        return build_server(STATE)
    if t == "http":
        return cfg["url"]
    if t == "stdio":
        return StdioServerParameters(command=cfg["command"], args=cfg.get("args", []), env=cfg.get("env") or None)
    raise ValueError(f"unknown MCP server type: {t}")


class MCPSession:
    """Opens all configured MCP servers for the duration of one agent run."""

    def __init__(self, servers: list[dict] | None):
        self.servers = [s for s in (servers if servers is not None else DEFAULT_SERVERS) if s.get("enabled", True)]
        self.clients: dict[str, Client] = {}
        self.tools: list[ToolInfo] = []
        self.errors: dict[str, str] = {}
        self._stack = AsyncExitStack()

    async def __aenter__(self) -> "MCPSession":
        for cfg in self.servers:
            name = cfg.get("name") or cfg.get("type", "mcp")
            try:
                client = Client(_target(cfg), read_timeout_seconds=20)
                await asyncio.wait_for(self._stack.enter_async_context(client), timeout=15)
                listed = await client.list_tools()
                self.clients[name] = client
                for t in listed.tools:
                    self.tools.append(ToolInfo(name, t.name, t.description or "", t.input_schema or {"type": "object"}))
            except Exception as e:  # a broken server must not break the chibi
                log.warning("MCP server %s unavailable: %s", name, e)
                self.errors[name] = str(e)
        return self

    async def __aexit__(self, *exc) -> None:
        try:
            await self._stack.aclose()
        except Exception as e:
            log.debug("MCP close: %s", e)

    def find(self, qualified: str) -> ToolInfo | None:
        return next((t for t in self.tools if t.qualified == qualified), None)

    def find_by_name(self, *names: str) -> ToolInfo | None:
        for n in names:
            for t in self.tools:
                if t.name == n:
                    return t
        return None

    async def call(self, qualified: str, args: dict) -> tuple[bool, str]:
        info = self.find(qualified)
        if not info:
            return False, f"tool {qualified} not found"
        res = await self.clients[info.server].call_tool(info.name, args)
        texts = []
        for c in getattr(res, "content", []) or []:
            texts.append(getattr(c, "text", "") or json.dumps(getattr(c, "model_dump", lambda: {})(), ensure_ascii=False))
        return not getattr(res, "is_error", False), "\n".join(texts)
