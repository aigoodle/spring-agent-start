"""Authenticated router from Agent Start Outbox messages to per-profile Hermes Unix sockets."""
import hmac
import json
import os
import re
import sqlite3
import threading
import asyncio
from contextlib import closing
from datetime import datetime, timezone
from pathlib import Path

from aiohttp import ClientSession, UnixConnector, web

SOCKET_DIR = Path(os.getenv("AGENT_START_BRIDGE_SOCKET_DIR", "/opt/data/agent-start-bridge"))
PROFILE = re.compile(r"^[A-Za-z0-9._-]{1,128}$")


class Ledger:
    def __init__(self):
        self.path = str(Path(os.getenv("HERMES_HOME", "/opt/data")) / "agent_start_bridge.sqlite3")
        self.lock = threading.RLock()
        with closing(sqlite3.connect(self.path)) as db:
            # v2 scopes caller-supplied keys by platform/profile so equal keys from two tenants cannot collide.
            db.execute("CREATE TABLE IF NOT EXISTS sends_v2 (scope_key TEXT PRIMARY KEY, "
                       "response_json TEXT NOT NULL, created_at TEXT NOT NULL)")
            db.commit()

    def get(self, key):
        if not key:
            return None
        with self.lock, closing(sqlite3.connect(self.path, timeout=10)) as db:
            row = db.execute("SELECT response_json FROM sends_v2 WHERE scope_key=?", (key,)).fetchone()
            return json.loads(row[0]) if row else None

    def put(self, key, value):
        if not key:
            return
        with self.lock, closing(sqlite3.connect(self.path, timeout=10)) as db:
            db.execute("INSERT OR IGNORE INTO sends_v2 VALUES(?,?,?)",
                       (key, json.dumps(value), datetime.now(timezone.utc).isoformat()))
            db.commit()


LEDGER = Ledger()


def authorized(request):
    expected = os.getenv("AGENT_START_BRIDGE_TOKEN", "")
    provided = request.headers.get("X-Agent-Start-Token", "")
    return bool(expected) and hmac.compare_digest(expected.encode(), provided.encode())


async def health(request):
    if not authorized(request):
        raise web.HTTPUnauthorized()
    SOCKET_DIR.mkdir(parents=True, exist_ok=True)
    sockets = sorted(SOCKET_DIR.glob("*.sock"), key=lambda path: path.stem)
    states = await asyncio.gather(*(_profile_health(path) for path in sockets))
    return web.json_response({"ok": True, "profiles": [path.stem for path in sockets],
                              "connectedProfiles": [state["profile"] for state in states
                                                    if state.get("connected")],
                              "profileStates": states})


async def _profile_health(socket):
    profile = socket.stem
    try:
        timeout = float(os.getenv("AGENT_START_BRIDGE_HEALTH_TIMEOUT_SECONDS", "2"))
        async with ClientSession(connector=UnixConnector(path=str(socket))) as session:
            async with session.get("http://hermes/health",
                                   headers={"X-Agent-Start-Token": os.getenv("AGENT_START_BRIDGE_TOKEN", "")},
                                   timeout=timeout) as response:
                value = await response.json()
                if response.status >= 300:
                    return {"profile": profile, "reachable": False, "connected": False,
                            "error": f"profile health returned HTTP {response.status}"}
                return {"profile": profile, "reachable": True,
                        "connected": bool(value.get("connected")), "platform": value.get("platform"),
                        "pendingInboundCallbacks": int(value.get("pendingInboundCallbacks") or 0),
                        "callbackWorkerRunning": bool(value.get("callbackWorkerRunning", False)),
                        "callbackWorkerFailures": int(value.get("callbackWorkerFailures") or 0),
                        "callbackWorkerError": value.get("callbackWorkerError")}
    except Exception as error:
        return {"profile": profile, "reachable": False, "connected": False,
                "error": f"{type(error).__name__}: {error}"}


async def send(request):
    if not authorized(request):
        raise web.HTTPUnauthorized()
    body = await request.json()
    profile = str(body.get("profile") or "default")
    platform = str(body.get("platform") or "")
    if not PROFILE.fullmatch(profile):
        raise web.HTTPBadRequest(text="invalid profile")
    metadata = body.get("metadata") if isinstance(body.get("metadata"), dict) else {}
    raw_key = str(metadata.get("idempotencyKey") or "")
    scoped_key = f"{platform}\0{profile}\0{raw_key}" if raw_key else ""
    cached = LEDGER.get(scoped_key)
    if cached is not None:
        return web.json_response(cached)
    socket = SOCKET_DIR / f"{profile}.sock"
    if not socket.exists():
        return web.json_response({"success": False, "error": f"profile adapter unavailable: {profile}"}, status=503)
    try:
        async with ClientSession(connector=UnixConnector(path=str(socket))) as session:
            async with session.post("http://hermes/send", json=body,
                                    headers={"X-Agent-Start-Token": os.getenv("AGENT_START_BRIDGE_TOKEN", "")}) as response:
                value = await response.json()
                status = response.status
    except Exception as error:
        return web.json_response({"success": False, "error": str(error)}, status=502)
    if status < 300 and value.get("success"):
        LEDGER.put(scoped_key, value)
    return web.json_response(value, status=status)


app = web.Application(client_max_size=10_000_000)
app.router.add_get("/health", health)
app.router.add_post("/v1/messages", send)

if __name__ == "__main__":
    SOCKET_DIR.mkdir(parents=True, exist_ok=True)
    web.run_app(app, host=os.getenv("AGENT_START_BRIDGE_HOST", "0.0.0.0"),
                port=int(os.getenv("AGENT_START_BRIDGE_PORT", "9121")), access_log=None)
