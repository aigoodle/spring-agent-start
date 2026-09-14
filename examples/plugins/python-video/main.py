"""A standalone HTTP v1 plugin. Its runtime can also contain a model SDK/FFmpeg.

The example prepares a brief, not an AI-generated video. No paid calls are made.
"""
import hmac
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from host_client import PluginHost

MANIFEST = json.loads(Path(__file__).with_name("manifest.json").read_text(encoding="utf-8"))


def execute(path, body):
    if body.get("protocolVersion") != "1" or body.get("pluginId") != MANIFEST["id"] or body.get("pluginVersion") != MANIFEST["version"]:
        raise ValueError("Unsupported plugin identity/version")
    invocation = body.get("invocation", {})
    if invocation.get("actionId") != "prepare":
        raise ValueError("Unknown action")
    inputs = invocation.get("inputs", {})
    product_id = inputs.get("productId")
    if not isinstance(product_id, str) or not product_id.strip():
        raise ValueError("productId is required")
    if path == "/v1/execute":
        return {"hostCall": {"id": "product", "capability": "product.read", "arguments": {"productId": product_id}, "state": {"phase": "brief"}}}
    if path == "/v1/resume":
        if body.get("hostCallId") != "product" or body.get("state") != {"phase": "brief"} or "hostResult" not in body:
            raise ValueError("Invalid continuation")
        text = f"制作商品视频脚本，风格：{inputs.get('style', '简洁')}"
        if inputs.get("modelId"):
            result = PluginHost(body.get("host")).chat(inputs["modelId"], [
                {"role": "system", "content": "你是一名商品视频策划。根据提供的商品资料输出视频脚本，不虚构商品属性。"},
                {"role": "user", "content": text + "\n商品资料：" + json.dumps(body["hostResult"], ensure_ascii=False)},
            ])
            text = result["text"]
        return {"completed": {"result": {"success": True, "data": {"text": text, "product": body["hostResult"]}, "content": [], "metadata": {"runtime": "python"}}}}
    raise ValueError("Unknown endpoint")


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass  # Never log bodies or credentials.

    def send_json(self, status, body):
        payload = json.dumps(body, ensure_ascii=False).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        self.wfile.write(payload)

    def do_GET(self):
        self.send_json(200 if self.path == "/health" else 404, {"status": "ready"} if self.path == "/health" else {"error": "not_found"})

    def do_POST(self):
        token = os.environ.get("PLUGIN_SERVICE_TOKEN", "")
        if not token or not hmac.compare_digest(self.headers.get("Authorization", ""), "Bearer " + token):
            self.send_json(401, {"error": "unauthorized"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size <= 0 or size > 1_048_576:
                raise ValueError("Invalid body size")
            body = json.loads(self.rfile.read(size))
            self.send_json(200, execute(self.path, body))
        except (ValueError, TypeError, AttributeError):
            self.send_json(400, {"error": "invalid_request"})
        except OSError:
            self.send_json(502, {"error": "host_unavailable"})


if __name__ == "__main__":
    if not os.environ.get("PLUGIN_SERVICE_TOKEN"):
        raise SystemExit("Set PLUGIN_SERVICE_TOKEN before starting")
    ThreadingHTTPServer(("0.0.0.0", int(os.environ.get("PORT", "8091"))), Handler).serve_forever()
