"""Stand-in for an external payment gateway (deliberately NOT instrumented).

It polls the shop's chaos API for the `external-latency` scenario and, while that is on,
answers slowly and sometimes fails. Shows up in APM as the inferred service `payment-gateway`.
"""
import json
import os
import random
import threading
import time
import urllib.request
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

CHAOS_URL = os.environ.get("CHAOS_URL", "http://app:8080/chaos/api/scenarios/external-latency")
PORT = int(os.environ.get("PORT", "8081"))

state = {"enabled": False, "intensity": 0}


def poll_chaos():
    while True:
        try:
            with urllib.request.urlopen(CHAOS_URL, timeout=2) as res:
                data = json.load(res)
                state["enabled"] = bool(data.get("enabled"))
                state["intensity"] = int(data.get("intensity", 0))
        except Exception:
            pass  # app restarting: keep the last known state
        time.sleep(2)


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):
        self.rfile.read(int(self.headers.get("Content-Length", 0)))
        delay = random.uniform(0.02, 0.08)
        status = 200
        if state["enabled"]:
            delay += state["intensity"] * 0.03 * random.uniform(0.6, 1.4)
            if random.random() < state["intensity"] / 400:
                status = 503
        time.sleep(delay)
        body = {"id": "pay_" + uuid.uuid4().hex[:12], "status": "captured"} if status == 200 else {"error": "upstream bank timeout"}
        payload = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(payload)))
        self.end_headers()
        try:
            self.wfile.write(payload)
        except BrokenPipeError:
            pass  # client already timed out

    def do_GET(self):
        self.send_response(200)
        self.end_headers()
        self.wfile.write(b"ok")

    def log_message(self, *args):
        pass


if __name__ == "__main__":
    threading.Thread(target=poll_chaos, daemon=True).start()
    ThreadingHTTPServer(("0.0.0.0", PORT), Handler).serve_forever()
