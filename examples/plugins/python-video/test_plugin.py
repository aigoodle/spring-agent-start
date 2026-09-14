import unittest
from main import execute, MANIFEST


class ProtocolTest(unittest.TestCase):
    def request(self):
        return {"protocolVersion": "1", "pluginId": MANIFEST["id"], "pluginVersion": MANIFEST["version"], "invocation": {"actionId": "prepare", "inputs": {"productId": "123"}}}

    def test_host_query_then_resume(self):
        request = self.request()
        call = execute("/v1/execute", request)["hostCall"]
        self.assertEqual(call["arguments"], {"productId": "123"})
        request.update(hostCallId=call["id"], state=call["state"], hostResult={"title": "Example"})
        result = execute("/v1/resume", request)["completed"]["result"]
        self.assertTrue(result["success"])
        self.assertEqual(result["data"]["product"], {"title": "Example"})

    def test_version_mismatch_rejected(self):
        request = self.request()
        request["pluginVersion"] = "2.0.0"
        with self.assertRaises(ValueError):
            execute("/v1/execute", request)

    def test_resume_requires_matching_state(self):
        with self.assertRaises(ValueError):
            execute("/v1/resume", self.request())


if __name__ == "__main__":
    unittest.main()
