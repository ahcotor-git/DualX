#!/usr/bin/env bash
set -euo pipefail
[[ "$(id -u)" == 0 ]] || { echo "Run with deployment administrator privileges."; exit 1; }
python3 - <<'PY'
import base64
import json
import re
import shlex
import stat
import subprocess
import time
import urllib.error
import urllib.request
import uuid
from pathlib import Path

def sql(statement):
    command = ('MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" '
               '"$MYSQL_DATABASE" -N -B -e ' + shlex.quote(statement))
    return subprocess.check_output(
        ["docker", "exec", "scds-validation-mysql", "sh", "-c", command],
        text=True).strip()

state = dict(line.split("=", 1) for line in
             Path("/opt/scds/.deploy-state").read_text().splitlines())
assert re.fullmatch(r"[0-9a-f]{40}", state["commit"])
assert re.fullmatch(r"[0-9a-f]{64}", state["jar_sha256"])
assert state["mode"] == "validation-loopback-only"
backend = json.loads(subprocess.check_output(
    ["docker", "inspect", "scds-validation-backend"], text=True))[0]
assert backend["State"]["Health"]["Status"] == "healthy"
assert backend["Config"]["User"] == "10001:10001"
assert backend["Config"]["Image"] == "scds-next:" + state["commit"]
assert backend["Config"]["Labels"]["org.opencontainers.image.revision"] == state["commit"]
assert backend["Config"]["Labels"]["scds.jar.sha256"] == state["jar_sha256"]
assert backend["Image"] == state["image_id"]
assert backend["HostConfig"]["ReadonlyRootfs"] is True
assert backend["HostConfig"]["PortBindings"] == {
    "11001/tcp": [{"HostIp": "127.0.0.1", "HostPort": "11001"}]}
actual_jar = subprocess.check_output(
    ["docker", "exec", "scds-validation-backend", "sha256sum", "/app/scds-next.jar"],
    text=True).split()[0]
assert actual_jar == state["jar_sha256"]
for filename, mode, gid in [(".env", 0o600, 0),
                            ("application.properties", 0o640, 10001),
                            ("jwt-private.pem", 0o640, 10001),
                            ("jwt-public.pem", 0o640, 10001)]:
    info = Path("/opt/scds/secrets", filename).stat()
    assert stat.S_IMODE(info.st_mode) == mode and info.st_uid == 0 and info.st_gid == gid
before = sql("SELECT (SELECT COUNT(*) FROM t_account),"
             "(SELECT COUNT(*) FROM t_refresh_token),"
             "(SELECT COUNT(*) FROM t_exercise);")
assert before == "1\t0\t0", "Unexpected validation database contents"
assert sql("SELECT COUNT(*) FROM flyway_schema_history WHERE success=1;") == "8"
assert sql("SELECT GROUP_CONCAT(version ORDER BY installed_rank) "
           "FROM flyway_schema_history WHERE success=1;") == "1,2,3,4,5,6,7,9"
assert sql("SELECT COUNT(*) FROM information_schema.tables "
           "WHERE table_schema=DATABASE();") == "19"
assert sql("SELECT TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(),NOW());") == "0"
assert sql("SELECT role_code FROM t_account_role;") == "ADMIN"
assert sql("SELECT COUNT(*) FROM t_account WHERE username='admin' "
           "AND LEFT(password_hash,7)='$2a$10$';") == "1"
account = str(uuid.UUID(sql("SELECT id FROM t_account WHERE username='admin' AND enabled=1;")))

def b64(value):
    return base64.urlsafe_b64encode(value).rstrip(b"=")

def token(roles):
    now = int(time.time())
    header = b64(json.dumps({"alg": "RS256", "typ": "JWT"}).encode())
    claims = b64(json.dumps({"iss": "scds-minipc-validation", "sub": account,
                            "aud": "scds-edge", "iat": now, "exp": now + 300,
                            "roles": roles}).encode())
    message = header + b"." + claims
    signature = subprocess.run(
        ["openssl", "dgst", "-sha256", "-sign", "/opt/scds/secrets/jwt-private.pem"],
        input=message, capture_output=True, check=True).stdout
    return (message + b"." + b64(signature)).decode()

def request(path, expected, bearer=None, payload=None):
    headers = {}
    if bearer:
        headers["Authorization"] = "Bearer " + bearer
    if payload is not None:
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request("http://127.0.0.1:11001" + path,
                                 data=payload, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response = error
    with response:
        body = json.loads(response.read())
        assert response.status == expected, f"{path}: unexpected status {response.status}"
        if expected >= 400:
            assert response.headers.get_content_type() == "application/problem+json"
            assert body.get("code") and body.get("traceId"), "Missing Problem extensions"
        print(f"{path}: {expected}")

request("/actuator/health", 200)
request("/api/v1/auth/me", 401)
request("/actuator/metrics", 401)
request("/actuator/metrics", 401, "invalid-validation-token")
request("/actuator/metrics", 200, token(["ADMIN"]))
request("/actuator/metrics", 403, token(["VIEWER"]))
request("/api/v1/auth/me", 501, token(["ADMIN"]))
request("/api/v1/auth/login", 400, payload=b"{}")
request("/api/v1/auth/login", 501, payload=json.dumps(
    {"username": "validation-not-an-account", "password": "not-a-real-password"}).encode())
assert sql("SELECT (SELECT COUNT(*) FROM t_account),"
           "(SELECT COUNT(*) FROM t_refresh_token),"
           "(SELECT COUNT(*) FROM t_exercise);") == before
assert "scds.admin.password=\n" in Path("/opt/scds/secrets/application.properties").read_text()
print("Read-only verification passed; database unchanged; bootstrap runtime password absent.")
print("Deployed commit: " + state["commit"])
PY
