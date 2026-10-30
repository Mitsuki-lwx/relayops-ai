"""Independent localhost webhook receiver; all files are local development artifacts."""
import argparse
import base64
from contextlib import closing
import hashlib
import hmac
import json
import re
import socket
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from uuid import UUID


def server(port, keys_file, database, fail_first=0, drop_first_response=False, reject_first=0):
    database = Path(database)
    database.parent.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(database)) as connection:
        connection.execute("CREATE TABLE IF NOT EXISTS inbox (delivery_id TEXT PRIMARY KEY, body_hash TEXT NOT NULL, body BLOB NOT NULL, received_at INTEGER NOT NULL)")
    gate = threading.Lock()
    seen = 0
    dropped = False

    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_args):
            pass  # Never log payloads, signatures or key material.

        def reply(self, code):
            self.send_response(code)
            self.send_header("Content-Length", "0")
            self.end_headers()

        def do_GET(self):
            self.reply(200 if self.path == "/ready" else 404)

        def do_POST(self):
            nonlocal seen, dropped
            if self.path != "/relayops/events":
                self.reply(404)
                return
            self.connection.settimeout(5)
            try:
                size = int(self.headers.get("Content-Length", "-1"))
                if not 0 < size <= 262144 or self.headers.get("Transfer-Encoding"):
                    self.reply(413)
                    return
                body = self.rfile.read(size)
                if len(body) != size:
                    self.reply(400)
                    return
                fields = {name: self.headers.get("X-Relay-" + name, "") for name in ("Delivery-Id", "Run-Id", "Attempt-Id", "Key-Id", "Timestamp", "Signature")}
                for name in ("Delivery-Id", "Run-Id", "Attempt-Id", "Key-Id"):
                    if str(UUID(fields[name])) != fields[name]:
                        raise ValueError()
                timestamp = int(fields["Timestamp"])
                if abs(int(time.time()) - timestamp) > 300 or not re.fullmatch(r"[0-9a-f]{64}", fields["Signature"]):
                    self.reply(401)
                    return
                keys = json.loads(Path(keys_file).read_text(encoding="utf-8"))
                key = keys[fields["Key-Id"]]
                body_hash = hashlib.sha256(body).hexdigest()
                signed = f"v1\n{timestamp}\n{fields['Delivery-Id']}\n{body_hash}".encode("ascii")
                expected = hmac.new(base64.b64decode(key["secret"], validate=True), signed, hashlib.sha256).hexdigest()
                if not hmac.compare_digest(expected, fields["Signature"]):
                    self.reply(401)
                    return
                payload = json.loads(body)
                if not isinstance(payload, dict) or payload.get("schemaVersion") != "relay.webhook.v1" or payload.get("workspaceId") != key["workspaceId"] or not isinstance(payload.get("result"), dict):
                    self.reply(400)
                    return
                with gate:
                    seen += 1
                    if seen <= reject_first:
                        self.reply(400)
                        return
                    if seen <= fail_first:
                        self.reply(500)
                        return
                # The dedupe fact and the demo business inbox row are the same transaction.
                with closing(sqlite3.connect(database, timeout=5)) as connection:
                    connection.execute("BEGIN IMMEDIATE")
                    previous = connection.execute("SELECT body_hash FROM inbox WHERE delivery_id=?", (fields["Delivery-Id"],)).fetchone()
                    if previous and previous[0] != body_hash:
                        self.reply(409)
                        return
                    if not previous:
                        connection.execute("INSERT INTO inbox VALUES (?,?,?,?)", (fields["Delivery-Id"], body_hash, body, int(time.time())))
                    connection.commit()
                with gate:
                    drop = drop_first_response and not dropped
                    if drop:
                        dropped = True
                if drop:
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    return
                self.reply(200)
            except (ValueError, KeyError, OSError, sqlite3.Error, json.JSONDecodeError):
                self.reply(400)

    return ThreadingHTTPServer(("127.0.0.1", port), Handler)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8091)
    parser.add_argument("--keys", default="artifacts/receiver/keys.json")
    parser.add_argument("--db", default="artifacts/receiver/inbox.sqlite")
    parser.add_argument("--fail-first", type=int, default=0)
    parser.add_argument("--drop-first-response", action="store_true")
    parser.add_argument("--reject-first", type=int, default=0)
    options = parser.parse_args()
    receiver = server(options.port, options.keys, options.db, options.fail_first, options.drop_first_response, options.reject_first)
    print(f"Local demo receiver listening on 127.0.0.1:{receiver.server_port}", flush=True)
    try:
        receiver.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        receiver.server_close()
