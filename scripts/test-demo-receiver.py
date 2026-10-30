import base64
from contextlib import closing
import hashlib
import hmac
import importlib.util
import json
from pathlib import Path
import socket
import sqlite3
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from uuid import uuid4

spec = importlib.util.spec_from_file_location("demo_receiver", Path(__file__).with_name("demo-receiver.py"))
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.folder = tempfile.TemporaryDirectory(prefix="relayops-receiver-")
        self.path = Path(self.folder.name)
        self.key_id, self.workspace, self.delivery = (str(uuid4()) for _ in range(3))
        self.secret = bytes(range(32))  # Public, deterministic test key only.
        (self.path / "keys.json").write_text(json.dumps({self.key_id: {"secret": base64.b64encode(self.secret).decode(), "workspaceId": self.workspace}}), encoding="utf-8")
        self.receiver = module.server(0, self.path / "keys.json", self.path / "inbox.sqlite")
        self.thread = threading.Thread(target=self.receiver.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.receiver.shutdown()
        self.receiver.server_close()
        self.thread.join(2)
        self.folder.cleanup()

    def send(self, body=None, timestamp=None, signature=None):
        body = body or json.dumps({"schemaVersion": "relay.webhook.v1", "workspaceId": self.workspace, "result": {"text": "原始字节不重写"}}, ensure_ascii=False).encode()
        timestamp = timestamp if timestamp is not None else int(time.time())
        digest = hashlib.sha256(body).hexdigest()
        signed = f"v1\n{timestamp}\n{self.delivery}\n{digest}".encode("ascii")
        signature = signature or hmac.new(self.secret, signed, hashlib.sha256).hexdigest()
        headers = {"X-Relay-Delivery-Id": self.delivery, "X-Relay-Run-Id": str(uuid4()), "X-Relay-Attempt-Id": str(uuid4()), "X-Relay-Key-Id": self.key_id, "X-Relay-Timestamp": str(timestamp), "X-Relay-Signature": signature, "Content-Type": "application/json"}
        request = urllib.request.Request(f"http://127.0.0.1:{self.receiver.server_port}/relayops/events", data=body, headers=headers, method="POST")
        try:
            with urllib.request.urlopen(request, timeout=3) as response:
                return response.status
        except urllib.error.HTTPError as error:
            return error.code

    def count(self):
        with closing(sqlite3.connect(self.path / "inbox.sqlite")) as connection:
            return connection.execute("SELECT COUNT(*) FROM inbox").fetchone()[0]

    def test_signature_window_atomic_dedup_and_changed_hash(self):
        self.assertEqual(401, self.send(signature="0" * 64))
        self.assertEqual(401, self.send(timestamp=int(time.time()) - 301))
        self.assertEqual(0, self.count())
        self.assertEqual(200, self.send())
        self.assertEqual(200, self.send())
        self.assertEqual(1, self.count())
        changed = json.dumps({"schemaVersion": "relay.webhook.v1", "workspaceId": self.workspace, "result": {"changed": True}}).encode()
        self.assertEqual(409, self.send(body=changed))
        self.assertEqual(1, self.count())

    def test_commit_then_lost_response_does_not_repeat_business_effect(self):
        self.receiver.shutdown()
        self.receiver.server_close()
        self.thread.join(2)
        self.receiver = module.server(0, self.path / "keys.json", self.path / "inbox.sqlite", drop_first_response=True)
        self.thread = threading.Thread(target=self.receiver.serve_forever, daemon=True)
        self.thread.start()
        with self.assertRaises((OSError, urllib.error.URLError, __import__("http.client", fromlist=["RemoteDisconnected"]).RemoteDisconnected)):
            self.send()
        self.assertEqual(1, self.count())
        self.assertEqual(200, self.send())
        self.assertEqual(1, self.count())


if __name__ == "__main__":
    unittest.main()
