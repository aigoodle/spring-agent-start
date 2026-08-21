import importlib
import os
import tempfile
import unittest
from pathlib import Path

from aiohttp import web
from aiohttp.test_utils import TestClient, TestServer


class RouterContractTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        os.environ["HERMES_HOME"] = self.temp.name
        os.environ["AGENT_START_BRIDGE_SOCKET_DIR"] = str(Path(self.temp.name) / "sockets")
        os.environ["AGENT_START_BRIDGE_TOKEN"] = "contract-token"
        import router
        self.router = importlib.reload(router)
        self.calls = {"employee-7": 0, "employee-8": 0}
        self.runners = []
        self.router.SOCKET_DIR.mkdir(parents=True, exist_ok=True)
        for profile in self.calls:
            async def send(request, current=profile):
                self.calls[current] += 1
                return web.json_response({"success": True, "messageId": f"qq-{current}", "error": None})
            async def health(request, current=profile):
                if request.headers.get("X-Agent-Start-Token") != "contract-token":
                    raise web.HTTPUnauthorized()
                return web.json_response({"ok": True, "profile": current, "platform": "qqbot",
                                          "connected": current == "employee-7",
                                          "pendingInboundCallbacks": 2 if current == "employee-8" else 0,
                                          "callbackWorkerRunning": current == "employee-7",
                                          "callbackWorkerFailures": 1 if current == "employee-8" else 0,
                                          "callbackWorkerError": "temporary queue failure" if current == "employee-8" else None})
            app = web.Application()
            app.router.add_post("/send", send)
            app.router.add_get("/health", health)
            runner = web.AppRunner(app)
            await runner.setup()
            await web.UnixSite(runner, str(self.router.SOCKET_DIR / f"{profile}.sock")).start()
            self.runners.append(runner)
        self.client = TestClient(TestServer(self.router.app))
        await self.client.start_server()

    async def asyncTearDown(self):
        await self.client.close()
        for runner in self.runners:
            await runner.cleanup()
        self.temp.cleanup()

    async def test_auth_profile_routing_and_profile_scoped_idempotency(self):
        headers = {"X-Agent-Start-Token": "contract-token"}
        base = {"platform": "qqbot", "chatId": "user-1", "content": "hello",
                "metadata": {"idempotencyKey": "tenant-local-key"}}
        unauthorized = await self.client.post("/v1/messages", json={**base, "profile": "employee-7"})
        self.assertEqual(401, unauthorized.status)
        for profile in ("employee-7", "employee-7", "employee-8"):
            response = await self.client.post("/v1/messages", json={**base, "profile": profile}, headers=headers)
            self.assertEqual(200, response.status)
        self.assertEqual({"employee-7": 1, "employee-8": 1}, self.calls)

    async def test_rejects_profile_path_traversal(self):
        response = await self.client.post("/v1/messages", headers={"X-Agent-Start-Token": "contract-token"},
                                          json={"profile": "../default", "chatId": "user-1", "metadata": {}})
        self.assertEqual(400, response.status)

    async def test_health_actively_distinguishes_connected_profiles(self):
        response = await self.client.get("/health", headers={"X-Agent-Start-Token": "contract-token"})
        self.assertEqual(200, response.status)
        body = await response.json()
        self.assertEqual(["employee-7", "employee-8"], body["profiles"])
        self.assertEqual(["employee-7"], body["connectedProfiles"])
        states = {state["profile"]: state for state in body["profileStates"]}
        self.assertTrue(states["employee-7"]["reachable"])
        self.assertTrue(states["employee-7"]["connected"])
        self.assertTrue(states["employee-8"]["reachable"])
        self.assertFalse(states["employee-8"]["connected"])
        self.assertEqual(2, states["employee-8"]["pendingInboundCallbacks"])
        self.assertTrue(states["employee-7"]["callbackWorkerRunning"])
        self.assertFalse(states["employee-8"]["callbackWorkerRunning"])
        self.assertEqual(1, states["employee-8"]["callbackWorkerFailures"])


if __name__ == "__main__":
    unittest.main()
