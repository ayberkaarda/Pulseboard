"""A local HTTP server that records requests and replies with scripted statuses."""

import threading
from collections.abc import Iterator
from contextlib import contextmanager
from dataclasses import dataclass, field
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


@dataclass
class RecordedRequest:
    """One request as received on the wire."""

    path: str
    headers: dict[str, str]
    body: bytes


@dataclass
class FakeApiCore:
    """Scripted responses (last one repeats) and the requests seen so far."""

    statuses: list[int] = field(default_factory=lambda: [202])
    requests: list[RecordedRequest] = field(default_factory=list)
    url: str = ""
    _lock: threading.Lock = field(default_factory=threading.Lock)

    def next_status(self, request: RecordedRequest) -> int:
        with self._lock:
            index = len(self.requests)
            self.requests.append(request)
        return self.statuses[min(index, len(self.statuses) - 1)]


@contextmanager
def fake_api_core(statuses: list[int] | None = None) -> Iterator[FakeApiCore]:
    """Serve on an ephemeral localhost port for the duration of the context."""
    state = FakeApiCore(statuses=statuses if statuses is not None else [202])

    class Handler(BaseHTTPRequestHandler):
        def do_POST(self) -> None:
            length = int(self.headers.get("Content-Length", "0"))
            body = self.rfile.read(length)
            headers = {key.lower(): value for key, value in self.headers.items()}
            status = state.next_status(RecordedRequest(self.path, headers, body))
            payload = b'{"accepted":0,"unknown":0}'
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, format: str, *args: object) -> None:
            return

    server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
    state.url = f"http://127.0.0.1:{server.server_address[1]}"
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield state
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=5)
