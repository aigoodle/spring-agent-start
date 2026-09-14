"""Invocation-scoped client for non-streaming platform capabilities."""
import json
from urllib.request import Request, urlopen


class PluginHost:
    def __init__(self, access):
        if not access or not access.get("baseUrl") or not access.get("token"):
            raise ValueError("Host HTTP access is not configured")
        self._base = access["baseUrl"].rstrip("/")
        self._token = access["token"]

    def chat(self, model_id, messages, *, max_tokens=1024, temperature=0.7):
        payload = {"modelId": model_id, "messages": messages, "maxTokens": max_tokens, "temperature": temperature, "stream": False}
        request = Request(self._base + "/models/chat", data=json.dumps(payload).encode(), method="POST", headers={"Authorization": "Bearer " + self._token, "Content-Type": "application/json"})
        with urlopen(request, timeout=60) as response:
            return json.load(response)

    def __repr__(self):
        return "PluginHost(<invocation credentials redacted>)"
