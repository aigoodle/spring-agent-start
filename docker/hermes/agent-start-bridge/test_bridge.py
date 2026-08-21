import importlib
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch


class InboundCallbackQueueTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        os.environ["HERMES_HOME"] = self.temp.name
        import bridge
        self.bridge = importlib.reload(bridge)
        self.queue = self.bridge.InboundCallbackQueue(Path(self.temp.name) / "queue.sqlite3")

    def tearDown(self):
        self.temp.cleanup()

    def payload(self, profile="employee-7", message="message-1"):
        return {"provider": "hermes", "channelId": "qqbot", "accountId": profile,
                "messageId": message, "content": "hello"}

    def test_duplicate_callback_is_stored_once_and_survives_reopen(self):
        self.queue.enqueue(self.payload())
        self.queue.enqueue(self.payload())
        reopened = self.bridge.InboundCallbackQueue(self.queue.path)
        self.assertEqual(1, reopened.pending())
        self.assertEqual("message-1", reopened.due()[0][1]["messageId"])

    def test_profile_scopes_equal_platform_message_ids(self):
        self.queue.enqueue(self.payload("employee-7"))
        self.queue.enqueue(self.payload("employee-8"))
        self.assertEqual(2, self.queue.pending())

    def test_failure_backs_off_and_delivery_removes_item(self):
        key = self.queue.enqueue(self.payload())
        row = self.queue.due()[0]
        self.queue.failed(key, row[2], "backend unavailable")
        self.assertEqual([], self.queue.due())
        with self.queue.lock, self.bridge.closing(self.bridge.sqlite3.connect(self.queue.path)) as db:
            db.execute("UPDATE inbound_callbacks SET next_attempt_at=?", (time.time() - 1,))
            db.commit()
        self.assertEqual(1, len(self.queue.due()))
        self.queue.delivered(key)
        self.assertEqual(0, self.queue.pending())

    def test_message_id_is_required_for_reliable_delivery(self):
        with self.assertRaises(ValueError):
            self.queue.enqueue({"provider": "hermes", "channelId": "qqbot", "accountId": "p"})


class CallbackWorkerRecoveryTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.temp = tempfile.TemporaryDirectory()
        os.environ["HERMES_HOME"] = self.temp.name
        import bridge
        self.bridge = importlib.reload(bridge)

    async def asyncTearDown(self):
        self.temp.cleanup()

    async def test_worker_recovers_after_transient_queue_failure(self):
        class Worker:
            _callback_worker_error = None
            _callback_worker_failures = 0

        worker = Worker()
        worker._flush_callbacks = AsyncMock(side_effect=[OSError("database is locked"), None])
        with patch.object(self.bridge.asyncio, "sleep",
                          AsyncMock(side_effect=[None, self.bridge.asyncio.CancelledError()])), \
                self.assertRaises(self.bridge.asyncio.CancelledError):
            await self.bridge.AgentStartQQAdapter._callback_loop(worker)

        self.assertEqual(2, worker._flush_callbacks.await_count)
        self.assertEqual(0, worker._callback_worker_failures)
        self.assertIsNone(worker._callback_worker_error)


if __name__ == "__main__":
    unittest.main()
