import json
import urllib.error
import urllib.request
from http.cookiejar import CookieJar

import pytest

from totem.config import Config
from totem.engine import Engine
from totem.hardware import SimulatedRelayBoard
from totem.web import start_web


@pytest.fixture
def panel():
    cfg = Config()
    cfg.web.host, cfg.web.port = "127.0.0.1", 0
    cfg.web.admin_password, cfg.web.viewer_password = "admin-pw", "viewer-pw"
    engine = Engine(cfg, SimulatedRelayBoard())
    engine.tick()
    server = start_web(engine, cfg.web)
    yield engine, f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def client(base, password=None):
    jar = CookieJar()
    opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(jar), NoRedirect)
    if password is not None:
        try:
            opener.open(base + "/login", data=f"password={password}".encode())
        except urllib.error.HTTPError as e:
            assert e.code == 303
    return opener


def call(opener, url, body=None, content_type="application/json"):
    req = urllib.request.Request(url, data=None if body is None else json.dumps(body).encode())
    if body is not None:
        req.add_header("Content-Type", content_type)
    try:
        with opener.open(req) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def test_unauthenticated_cannot_control(panel):
    # main.php redirected unauthenticated users but kept executing, so anyone
    # could post relay overrides, sequences and rewrite the config file.
    engine, base = panel
    anon = client(base)
    assert call(anon, base + "/api/override", {"banks": [255] * 19})[0] == 401
    assert call(anon, base + "/api/sequence", {"name": "blank"})[0] == 401
    assert call(anon, base + "/api/status")[0] == 401
    assert engine.commands.empty()


def test_wrong_password_rejected(panel):
    engine, base = panel
    opener = client(base, "nope")
    assert call(opener, base + "/api/status")[0] == 401


def test_viewer_is_read_only(panel):
    engine, base = panel
    viewer = client(base, "viewer-pw")
    status, body = call(viewer, base + "/api/status")
    assert status == 200 and json.loads(body)["role"] == "viewer"
    assert call(viewer, base + "/api/auto", {})[0] == 403
    assert engine.commands.empty()


def test_admin_controls_with_validation(panel):
    engine, base = panel
    admin = client(base, "admin-pw")
    assert call(admin, base + "/api/override", {"banks": [1] * 19})[0] == 200
    assert call(admin, base + "/api/override", {"banks": [256] * 19})[0] == 400
    assert call(admin, base + "/api/override", {"banks": [1] * 3})[0] == 400
    assert call(admin, base + "/api/sequence", {"name": "../../etc"})[0] == 400
    assert call(admin, base + "/api/sequence", {"name": "diagonal_chase"})[0] == 200
    assert engine.commands.qsize() == 2


def test_form_posts_rejected_to_block_csrf(panel):
    engine, base = panel
    admin = client(base, "admin-pw")
    status, _ = call(admin, base + "/api/auto", {}, content_type="application/x-www-form-urlencoded")
    assert status == 415
    assert engine.commands.empty()


def test_pages_served(panel):
    engine, base = panel
    status, body = call(client(base), base + "/")
    assert status == 200 and b"Password" in body
    status, body = call(client(base, "admin-pw"), base + "/")
    assert status == 200 and b"Live view" in body


def test_layout_and_assets(panel):
    engine, base = panel
    admin = client(base, "admin-pw")
    status, body = call(admin, base + "/api/layout")
    flowers = json.loads(body)["flowers"]
    assert status == 200 and len(flowers) == 36 and all(len(f["petals"]) == 3 for f in flowers)
    assert call(client(base), base + "/api/layout")[0] == 401
    for asset in ("/app.css", "/app.js", "/favicon.svg"):
        assert call(client(base), base + asset)[0] == 200


def test_laser_endpoint(panel):
    engine, base = panel
    assert call(client(base, "viewer-pw"), base + "/api/laser", {})[0] == 403
    assert call(client(base, "admin-pw"), base + "/api/laser", {})[0] == 200
    assert engine.commands.get_nowait() == ("laser", None)
