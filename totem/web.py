"""Password-protected web control panel."""

from __future__ import annotations

import hmac
import json
import logging
import secrets
import threading
import time
from http import HTTPStatus
from http.cookies import SimpleCookie
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from importlib import resources
from urllib.parse import parse_qs, urlsplit

from . import sequences
from .config import WebConfig
from .engine import Engine
from .model import NUM_BANKS

log = logging.getLogger(__name__)

ADMIN = "admin"
VIEWER = "viewer"
SESSION_SECONDS = 12 * 3600
COOKIE = "totem_session"
MAX_BODY = 16 * 1024

SENSOR_LABELS = [f"Motion {i + 1}" for i in range(6)] + [
    "Wind 1", "Wind 2", "Rain", "Light", "Aux 1", "Aux 2", "Aux 3"
]


class Sessions:
    def __init__(self) -> None:
        self._sessions: dict[str, tuple[str, float]] = {}
        self._lock = threading.Lock()

    def create(self, role: str) -> str:
        token = secrets.token_urlsafe(32)
        with self._lock:
            now = time.time()
            self._sessions = {t: v for t, v in self._sessions.items() if v[1] > now}
            self._sessions[token] = (role, now + SESSION_SECONDS)
        return token

    def role(self, token: str | None) -> str | None:
        if not token:
            return None
        with self._lock:
            entry = self._sessions.get(token)
            if entry is None or entry[1] < time.time():
                self._sessions.pop(token, None)
                return None
            return entry[0]

    def drop(self, token: str | None) -> None:
        with self._lock:
            self._sessions.pop(token or "", None)


def check_password(cfg: WebConfig, password: str) -> str | None:
    given = password.encode()
    # Compare against both so timing does not reveal which (if either) matched.
    is_admin = bool(cfg.admin_password) and hmac.compare_digest(given, cfg.admin_password.encode())
    is_viewer = bool(cfg.viewer_password) and hmac.compare_digest(given, cfg.viewer_password.encode())
    if is_admin:
        return ADMIN
    if is_viewer:
        return VIEWER
    return None


def parse_override(body: object) -> dict[int, int]:
    """Validate {"banks": [v1, ..., v19]} into {bank_index: value}."""
    if not isinstance(body, dict) or not isinstance(body.get("banks"), list):
        raise ValueError("expected {\"banks\": [...]}")
    banks = body["banks"]
    if len(banks) != NUM_BANKS:
        raise ValueError(f"expected {NUM_BANKS} bank values")
    for v in banks:
        if isinstance(v, bool) or not isinstance(v, int) or not 0 <= v <= 255:
            raise ValueError("bank values must be integers 0-255")
    return dict(enumerate(banks))


def _page(name: str) -> bytes:
    return resources.files(__package__).joinpath("static", name).read_bytes()


def make_handler(engine: Engine, cfg: WebConfig, sessions: Sessions):
    class Handler(BaseHTTPRequestHandler):
        server_version = "Totem"

        def log_message(self, fmt, *args):
            log.debug("%s - %s", self.address_string(), fmt % args)

        # ---- helpers ----
        def _token(self) -> str | None:
            cookie = SimpleCookie(self.headers.get("Cookie", ""))
            morsel = cookie.get(COOKIE)
            return morsel.value if morsel else None

        def _role(self) -> str | None:
            return sessions.role(self._token())

        def _send(self, status: int, body: bytes, content_type: str, headers: dict | None = None):
            self.send_response(status)
            self.send_header("Content-Type", content_type)
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Frame-Options", "DENY")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'")
            for k, v in (headers or {}).items():
                self.send_header(k, v)
            self.end_headers()
            self.wfile.write(body)

        def _json(self, status: int, data: object):
            self._send(status, json.dumps(data).encode(), "application/json")

        def _redirect(self, location: str, headers: dict | None = None):
            self._send(HTTPStatus.SEE_OTHER, b"", "text/plain", {"Location": location, **(headers or {})})

        def _body(self) -> bytes:
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                raise ValueError("request too large")
            return self.rfile.read(length)

        # ---- routes ----
        def do_GET(self):
            role = self._role()
            path = urlsplit(self.path).path
            if path == "/":
                self._send(HTTPStatus.OK, _page("index.html" if role else "login.html"), "text/html; charset=utf-8")
            elif path == "/app.js":
                self._send(HTTPStatus.OK, _page("app.js"), "text/javascript; charset=utf-8")
            elif path == "/api/status":
                if not role:
                    return self._json(HTTPStatus.UNAUTHORIZED, {"error": "not logged in"})
                self._json(
                    HTTPStatus.OK,
                    {
                        **engine.status(),
                        "role": role,
                        "sensor_labels": SENSOR_LABELS,
                        "sequences": [
                            {"name": n, "label": label} for n, (label, _) in sequences.REGISTRY.items()
                        ],
                    },
                )
            else:
                self._send(HTTPStatus.NOT_FOUND, b"not found", "text/plain")

        def do_POST(self):
            try:
                body = self._body()
            except ValueError as exc:
                return self._json(HTTPStatus.REQUEST_ENTITY_TOO_LARGE, {"error": str(exc)})

            if self.path == "/login":
                form = parse_qs(body.decode(errors="replace"))
                role = check_password(cfg, form.get("password", [""])[0])
                if role is None:
                    time.sleep(1)  # slow down guessing
                    return self._redirect("/?failed=1")
                token = sessions.create(role)
                return self._redirect(
                    "/", {"Set-Cookie": f"{COOKIE}={token}; HttpOnly; SameSite=Strict; Path=/; Max-Age={SESSION_SECONDS}"}
                )
            if self.path == "/logout":
                sessions.drop(self._token())
                return self._redirect("/", {"Set-Cookie": f"{COOKIE}=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0"})

            # API calls: admins only, JSON only (a cross-site form cannot send JSON).
            role = self._role()
            if role is None:
                return self._json(HTTPStatus.UNAUTHORIZED, {"error": "not logged in"})
            if role != ADMIN:
                return self._json(HTTPStatus.FORBIDDEN, {"error": "read-only account"})
            if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
                return self._json(HTTPStatus.UNSUPPORTED_MEDIA_TYPE, {"error": "expected application/json"})
            try:
                data = json.loads(body or b"{}")
                if self.path == "/api/override":
                    engine.submit("override", parse_override(data))
                elif self.path == "/api/sequence":
                    name = data.get("name") if isinstance(data, dict) else None
                    if name not in sequences.REGISTRY:
                        raise ValueError("unknown sequence")
                    engine.submit("sequence", name)
                elif self.path == "/api/auto":
                    engine.submit("auto")
                else:
                    return self._json(HTTPStatus.NOT_FOUND, {"error": "not found"})
            except (ValueError, json.JSONDecodeError) as exc:
                return self._json(HTTPStatus.BAD_REQUEST, {"error": str(exc)})
            log.info("web: %s from %s", self.path, self.client_address[0])
            self._json(HTTPStatus.OK, {"ok": True})

    return Handler


def start_web(engine: Engine, cfg: WebConfig) -> ThreadingHTTPServer:
    if not cfg.admin_password:
        cfg.admin_password = secrets.token_urlsafe(12)
        log.warning("no web admin_password configured; generated one for this run: %s", cfg.admin_password)
    server = ThreadingHTTPServer((cfg.host, cfg.port), make_handler(engine, cfg, Sessions()))
    server.daemon_threads = True
    threading.Thread(target=server.serve_forever, name="web", daemon=True).start()
    log.info("web panel on http://%s:%d/", cfg.host, cfg.port)
    return server
