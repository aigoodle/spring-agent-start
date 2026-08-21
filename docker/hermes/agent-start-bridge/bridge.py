"""Per-profile Agent Start bridge wrapping Hermes' official QQAdapter."""
import asyncio
import hmac
import json
import os
import sqlite3
import threading
import time
import urllib.request
from contextlib import closing, suppress
from datetime import datetime, timezone
from pathlib import Path

from aiohttp import web
from gateway.platforms.qqbot import QQAdapter, check_qq_requirements
from hermes_cli.config import get_hermes_home


class InboundCallbackQueue:
    """Durable per-profile inbox-to-backend delivery queue."""
    def __init__(self, path=None):
        self.path = str(path or (Path(get_hermes_home()) / "agent_start_bridge.sqlite3"))
        self.lock = threading.RLock()
        Path(self.path).parent.mkdir(parents=True, exist_ok=True)
        with closing(sqlite3.connect(self.path)) as db:
            db.execute("CREATE TABLE IF NOT EXISTS inbound_callbacks ("
                       "scope_key TEXT PRIMARY KEY, payload_json TEXT NOT NULL, attempts INTEGER NOT NULL, "
                       "next_attempt_at REAL NOT NULL, last_error TEXT, created_at TEXT NOT NULL)")
            db.commit()

    @staticmethod
    def key(payload):
        message_id = str(payload.get("messageId") or "").strip()
        if not message_id:
            raise ValueError("Hermes inbound messageId is required")
        return "\0".join((str(payload.get("accountId") or "default"),
                           str(payload.get("channelId") or "unknown"), message_id))

    def enqueue(self, payload):
        key = self.key(payload)
        with self.lock, closing(sqlite3.connect(self.path, timeout=10)) as db:
            db.execute("INSERT OR IGNORE INTO inbound_callbacks VALUES(?,?,?,?,?,?)",
                       (key, json.dumps(payload, ensure_ascii=False), 0, time.time(), None,
                        datetime.now(timezone.utc).isoformat()))
            db.commit()
        return key

    def due(self, limit=20):
        with self.lock, closing(sqlite3.connect(self.path, timeout=10)) as db:
            rows = db.execute("SELECT scope_key,payload_json,attempts FROM inbound_callbacks "
                              "WHERE next_attempt_at<=? ORDER BY next_attempt_at,created_at LIMIT ?",
                              (time.time(), max(1, min(int(limit), 100)))).fetchall()
        return [(key, json.loads(payload), attempts) for key, payload, attempts in rows]

    def delivered(self, key):
        with self.lock, closing(sqlite3.connect(self.path, timeout=10)) as db:
            db.execute("DELETE FROM inbound_callbacks WHERE scope_key=?", (key,))
            db.commit()

    def failed(self, key, attempts, error):
        attempts = int(attempts) + 1
        delay = min(300, 2 ** min(attempts, 8))
        with self.lock, closing(sqlite3.connect(self.path, timeout=10)) as db:
            db.execute("UPDATE inbound_callbacks SET attempts=?,next_attempt_at=?,last_error=? WHERE scope_key=?",
                       (attempts, time.time() + delay, str(error)[:1000], key))
            db.commit()

    def pending(self):
        with self.lock, closing(sqlite3.connect(self.path, timeout=10)) as db:
            return int(db.execute("SELECT COUNT(*) FROM inbound_callbacks").fetchone()[0])


def _profile_name():
    home = Path(get_hermes_home()).resolve()
    parts = home.parts
    if "profiles" in parts:
        index = parts.index("profiles")
        if index + 1 < len(parts):
            return parts[index + 1]
    return os.getenv("HERMES_PROFILE_NAME") or os.getenv("HERMES_PROFILE") or "default"


def _value(value):
    return str(getattr(value, "value", value) or "qqbot")


def _timestamp(value):
    if isinstance(value, datetime):
        if value.tzinfo is None:
            value = value.replace(tzinfo=timezone.utc)
        return value.isoformat()
    return datetime.now(timezone.utc).isoformat()


def _attachments(event):
    result = []
    urls = list(getattr(event, "media_urls", None) or [])
    types = list(getattr(event, "media_types", None) or [])
    for index, url in enumerate(urls):
        result.append({"type": str(types[index] if index < len(types) else "file").upper(),
                       "url": str(url), "metadata": {}})
    return result


def _payload(adapter, event):
    source = event.source
    platform = _value(getattr(source, "platform", None))
    chat = str(getattr(source, "chat_id", "") or "")
    thread = getattr(source, "thread_id", None)
    conversation = f"{platform}:{chat}" + (f":{thread}" if thread else "")
    return {"provider": "hermes",
            "runtimeNodeId": os.getenv("AGENT_START_RUNTIME_NODE_ID", "hermes-default"),
            "channelId": platform, "accountId": adapter.agent_start_profile,
            "messageId": str(getattr(event, "message_id", "") or ""),
            "senderId": str(getattr(event, "user_id", None) or getattr(source, "user_id", "") or ""),
            "conversationId": conversation, "content": str(getattr(event, "text", "") or ""),
            "messageType": _value(getattr(event, "message_type", "text")).upper(),
            "attachments": _attachments(event), "contentPayload": {},
            "timestamp": _timestamp(getattr(event, "timestamp", None)),
            "group": str(getattr(source, "chat_type", "dm")) != "dm",
            "metadata": {"chatId": chat, "threadId": str(thread) if thread is not None else None,
                         "chatType": getattr(source, "chat_type", "dm"),
                         "userName": getattr(event, "user_name", None) or getattr(source, "user_name", None),
                         "profile": adapter.agent_start_profile}}


def _post(payload):
    url = os.getenv("AGENT_START_BRIDGE_URL", "").rstrip("/")
    token = os.getenv("AGENT_START_BRIDGE_TOKEN", "")
    if not url or not token:
        raise RuntimeError("Agent Start bridge callback is not configured")
    request = urllib.request.Request(url, data=json.dumps(payload, ensure_ascii=False).encode(), method="POST",
                                     headers={"Content-Type": "application/json",
                                              "X-Agent-Start-Token": token})
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.loads(response.read().decode())


def _authorized(request):
    expected = os.getenv("AGENT_START_BRIDGE_TOKEN", "")
    provided = request.headers.get("X-Agent-Start-Token", "")
    return bool(expected) and hmac.compare_digest(expected.encode(), provided.encode())


async def _dispatch(adapter, chat, payload, metadata):
    content = str(payload.get("content") or "")
    reply = str(metadata.get("replyToPlatformMessageId") or "") or None
    attachments = payload.get("attachments") if isinstance(payload.get("attachments"), list) else []
    last = None
    for item in attachments:
        if not isinstance(item, dict) or not item.get("url"):
            continue
        kind = str(item.get("type") or "FILE").upper()
        source = str(item["url"])
        if kind in {"IMAGE", "PHOTO"}:
            last = await adapter.send_image(chat, source, caption=content or None, reply_to=reply)
        elif kind in {"AUDIO", "VOICE"}:
            last = await adapter.send_voice(chat, source, caption=content or None, reply_to=reply)
        elif kind == "VIDEO":
            last = await adapter.send_video(chat, source, caption=content or None, reply_to=reply)
        else:
            last = await adapter.send_document(chat, source, caption=content or None,
                                               file_name=item.get("name"), reply_to=reply)
        if not last.success:
            return last
        content = ""
        reply = None
    return last or await adapter.send(chat, content, reply_to=reply, metadata={"notify": True})


class AgentStartQQAdapter(QQAdapter):
    def __init__(self, config):
        super().__init__(config)
        self.agent_start_profile = _profile_name()
        self._bridge_runner = None
        self._bridge_socket = None
        self._callback_queue = InboundCallbackQueue()
        self._callback_worker = None
        self._callback_flush_lock = asyncio.Lock()
        self._callback_worker_error = None
        self._callback_worker_failures = 0

    async def connect(self, *, is_reconnect=False):
        await self._start_endpoint()
        return await super().connect(is_reconnect=is_reconnect)

    async def disconnect(self):
        try:
            await super().disconnect()
        finally:
            await self._stop_endpoint()

    async def handle_message(self, event):
        if event.message_id and event.source.chat_id:
            self._last_msg_id[event.source.chat_id] = event.message_id
        payload = _payload(self, event)
        key = await asyncio.to_thread(self._callback_queue.enqueue, payload)
        delivered = await self._flush_callbacks(only_key=key)
        if not delivered:
            if os.getenv("AGENT_START_BRIDGE_FALLBACK_NATIVE", "").lower() in {"1", "true", "yes"}:
                await super().handle_message(event)
                await asyncio.to_thread(self._callback_queue.delivered, key)

    async def _flush_callbacks(self, only_key=None):
        async with self._callback_flush_lock:
            delivered_selected = False
            rows = await asyncio.to_thread(self._callback_queue.due, 50)
            for key, payload, attempts in rows:
                if only_key is not None and key != only_key:
                    continue
                try:
                    await asyncio.to_thread(_post, payload)
                    await asyncio.to_thread(self._callback_queue.delivered, key)
                    if key == only_key:
                        delivered_selected = True
                except Exception as error:
                    await asyncio.to_thread(self._callback_queue.failed, key, attempts, error)
            return delivered_selected

    async def _callback_loop(self):
        delay = 1
        while True:
            try:
                await self._flush_callbacks()
                self._callback_worker_error = None
                self._callback_worker_failures = 0
                delay = 1
            except asyncio.CancelledError:
                raise
            except Exception as error:
                # A transient SQLite/filesystem/backend queue error must not permanently stop
                # inbound delivery while the QQ transport still appears connected.
                self._callback_worker_failures += 1
                self._callback_worker_error = f"{type(error).__name__}: {error}"[:1000]
                delay = min(30, 2 ** min(self._callback_worker_failures, 5))
            await asyncio.sleep(delay)

    async def _start_endpoint(self):
        if self._bridge_runner is not None:
            return
        directory = Path(os.getenv("AGENT_START_BRIDGE_SOCKET_DIR", "/opt/data/agent-start-bridge"))
        directory.mkdir(parents=True, exist_ok=True)
        socket = directory / f"{self.agent_start_profile}.sock"
        socket.unlink(missing_ok=True)

        async def health(request):
            if not _authorized(request):
                raise web.HTTPUnauthorized()
            return web.json_response({"ok": True, "profile": self.agent_start_profile,
                                      "platform": "qqbot", "connected": bool(self.is_connected),
                                      "pendingInboundCallbacks": await asyncio.to_thread(
                                          self._callback_queue.pending),
                                      "callbackWorkerRunning": bool(self._callback_worker and
                                                                     not self._callback_worker.done()),
                                      "callbackWorkerFailures": self._callback_worker_failures,
                                      "callbackWorkerError": self._callback_worker_error})

        async def send(request):
            if not _authorized(request):
                raise web.HTTPUnauthorized()
            body = await request.json()
            chat = str(body.get("chatId") or "")
            if not chat:
                raise web.HTTPBadRequest(text="chatId is required")
            metadata = body.get("metadata") if isinstance(body.get("metadata"), dict) else {}
            result = await _dispatch(self, chat, body, metadata)
            value = {"success": bool(result.success), "messageId": result.message_id, "error": result.error}
            return web.json_response(value, status=200 if result.success else 502)

        app = web.Application(client_max_size=10_000_000)
        app.router.add_get("/health", health)
        app.router.add_post("/send", send)
        runner = web.AppRunner(app)
        await runner.setup()
        await web.UnixSite(runner, str(socket)).start()
        os.chmod(socket, 0o660)
        self._bridge_runner = runner
        self._bridge_socket = socket
        if self._callback_worker is None or self._callback_worker.done():
            self._callback_worker = asyncio.create_task(self._callback_loop(),
                                                        name="agent-start-inbound-outbox")

    async def _stop_endpoint(self):
        if self._callback_worker is not None:
            self._callback_worker.cancel()
            with suppress(asyncio.CancelledError):
                await self._callback_worker
        if self._bridge_runner is not None:
            await self._bridge_runner.cleanup()
        if self._bridge_socket is not None:
            self._bridge_socket.unlink(missing_ok=True)
        self._bridge_runner = None
        self._bridge_socket = None
        self._callback_worker = None


def register(ctx):
    ctx.register_platform(name="qqbot", label="QQ Bot (Agent Start)", adapter_factory=AgentStartQQAdapter,
                          check_fn=check_qq_requirements, required_env=["QQ_APP_ID", "QQ_CLIENT_SECRET"],
                          allowed_users_env="QQ_ALLOWED_USERS", allow_all_env="QQ_ALLOW_ALL_USERS",
                          max_message_length=2000, emoji="🐧", allow_update_command=False)
