"""Exercise an old-to-new backend upgrade on disposable local data, with ESL off.

Requires Python 3 and two Windows backend bundles. Never touches live databases.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import subprocess
import time
import urllib.error
import urllib.request


def request(origin, path, token=None, body=None, method=None):
    headers = {"Authorization": "Bearer " + token} if token else {}
    if body is not None:
        headers["Content-Type"] = "application/json"
        body = json.dumps(body).encode()
    req = urllib.request.Request(origin + path, data=body, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            raw = response.read()
            return response.status, json.loads(raw) if "json" in response.headers.get("Content-Type", "") else raw
    except urllib.error.HTTPError as error:
        return error.code, None


class Backend:
    def __init__(self, bundle, data, logs, label):
        self.log = logs / (label + ".stdout.log")
        self.out = self.log.open("wb")
        self.err = (logs / (label + ".stderr.log")).open("wb")
        self.process = subprocess.Popen([
            str(bundle / "jre/bin/java.exe"), "-jar", str(bundle / "lib/sipim-admin.jar"),
            "--server.address=127.0.0.1", "--server.port=0", "--freeswitch.esl.enabled=false",
            "--spring.datasource.url=jdbc:sqlite:" + str(data / "sipim.db"),
            "--sipim.files.upload-dir=" + str(data / "uploads"),
            "--sipim.avatar.upload-dir=" + str(data / "avatar-uploads"),
            "--sipim.avatar.fs-dir=" + str(data / "avatar-fs")],
            cwd=data, stdout=self.out, stderr=self.err,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        try:
            deadline = time.monotonic() + 45
            while time.monotonic() < deadline:
                if self.process.poll() is not None:
                    raise RuntimeError("Backend exited; inspect " + str(self.log))
                match = re.search(r"Tomcat started on port (\d+)", self.log.read_text(encoding="utf-8", errors="replace"))
                if match:
                    self.origin = "http://127.0.0.1:" + match[1]
                    status, result = request(self.origin, "/api/admin/login", body={"username": "1001", "password": "1001"})
                    if status == 200:
                        self.token = result["token"]
                        return
                time.sleep(.1)
            raise RuntimeError("Backend startup timed out")
        except BaseException:
            self.close()
            raise

    def api(self, path, body=None, method=None):
        return request(self.origin, path, self.token, body, method)

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=5)
        self.out.close()
        self.err.close()


def account_hashes(database):
    with sqlite3.connect(database) as connection:
        return dict(connection.execute("SELECT username,password_hash FROM users"))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, required=True)
    parser.add_argument("--candidate", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    baseline, candidate, root = args.baseline.resolve(), args.candidate.resolve(), args.output.resolve()
    if root.exists():
        raise ValueError("Output must be a new directory")
    root.mkdir(parents=True)
    data = root / "data"
    data.mkdir()
    app = None
    try:
        app = Backend(baseline, data, root, "baseline")
        assert app.api("/api/cdr", {"caller": "1001", "callee_or_room": "1008", "duration_sec": 22})[0] == 200
        assert app.api("/api/messages", {"receiver": "1008", "content": "升级前保留的记录", "status": "DELIVERED", "isFile": False})[0] in (200, 201)
        status, retired = app.api("/api/register", {"username": "upgrade-retired", "password": "test-only"})
        assert status == 201
        assert app.api("/api/messages", {"receiver": "upgrade-retired", "content": "历史身份保留", "status": "DELIVERED", "isFile": False})[0] in (200, 201)
        assert app.api("/api/users/" + str(retired["id"]), method="DELETE")[0] == 200
        _, original_users = app.api("/api/admin/workbench/users")
        target = next(u for u in original_users if u["username"] == "1008")
        # Fixture attachment bytes are backed up with the database; seed its index below.
        app.close()
        app = None
        uploads = data / "uploads"
        uploads.mkdir(exist_ok=True)
        attachment = uploads / "fixture_upgrade.txt"
        attachment.write_text("attachment before upgrade", encoding="utf-8")
        with sqlite3.connect(data / "sipim.db") as connection:
            # SQLite/JPA stores Instant using the same representation as existing CDR.
            timestamp = connection.execute("SELECT started_at FROM cdr LIMIT 1").fetchone()[0]
            connection.execute("INSERT INTO files(from_user,to_user_or_room,path,size,created_at) VALUES(?,?,?,?,?)",
                               ("1001", "1008", str(attachment), attachment.stat().st_size, timestamp))
        hashes = account_hashes(data / "sipim.db")
        shutil.copytree(data, root / "pre-upgrade-backup")
        app = Backend(candidate, data, root, "candidate-first")
        assert account_hashes(data / "sipim.db") == hashes
        _, current_users = app.api("/api/admin/workbench/users")
        assert len(current_users) == len(original_users)
        assert app.api("/api/admin/workbench/cdr/page")[1]["total"] == 1
        assert app.api("/api/admin/workbench/messages/page")[1]["total"] == 2
        _, files = app.api("/api/admin/workbench/files/page")
        assert files["total"] == 1
        assert app.api("/api/admin/workbench/files/" + str(files["items"][0]["id"]))[1] == attachment.read_bytes()
        assert app.api("/api/register", {"username": "upgrade-retired", "password": "test-only"})[0] == 409
        route = "/api/admin/workbench/users/" + str(target["id"]) + "/state"
        assert app.api(route, {"disabled": True}, "PATCH")[0] == 200
        assert request(app.origin, "/api/login", body={"username": "1008", "password": "1008"})[0] == 401
        assert app.api(route, {"disabled": False}, "PATCH")[0] == 200
        assert request(app.origin, "/api/login", body={"username": "1008", "password": "1008"})[0] == 200
        _, csv = app.api("/api/admin/workbench/cdr/export")
        assert csv.startswith(b"\xef\xbb\xbf")
        assert app.api("/api/admin/workbench/operations")[1]["esl"]["enabled"] is False
        _, audit = app.api("/api/admin/workbench/audit/page")
        audit_count = audit["total"]
        assert audit_count >= 3
        app.close()
        app = Backend(candidate, data, root, "candidate-restart")
        assert account_hashes(data / "sipim.db") == hashes
        assert app.api("/api/admin/workbench/audit/page")[1]["total"] >= audit_count
        assert app.api("/api/admin/workbench/cdr/page")[1]["total"] == 1
        app.close()
        app = None
        # Restore into a separate directory and prove the pre-upgrade data is intact.
        restored = root / "restore-check"
        shutil.copytree(root / "pre-upgrade-backup", restored)
        assert account_hashes(restored / "sipim.db") == hashes
        assert hashlib.sha256((restored / "uploads/fixture_upgrade.txt").read_bytes()).digest() == hashlib.sha256(attachment.read_bytes()).digest()
        upgraded = root / "upgraded-state-retained"
        assert data.resolve().parent == root and upgraded.resolve().parent == root and not upgraded.exists()
        data.rename(upgraded)
        shutil.copytree(root / "pre-upgrade-backup", data)
        app = Backend(baseline, data, root, "baseline-restored")
        assert app.api("/api/admin/workbench/stats")[1]["totalCalls"] == 1
        assert app.api("/api/admin/workbench/files/" + str(files["items"][0]["id"]))[1] == attachment.read_bytes()
        assert account_hashes(data / "sipim.db") == hashes
        app.close()
        app = None
        result = {"status": "PASS", "users": len(current_users), "calls": 1, "messages": 2, "files": 1,
                  "credentialsPreserved": True, "disabledRestore": True, "retiredIdentityReserved": True,
                  "auditSurvivesRestart": True, "backupRestore": True, "externalServiceUsed": False}
        (root / "result.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
        print(json.dumps(result))
    finally:
        if app is not None:
            app.close()


if __name__ == "__main__":
    main()
