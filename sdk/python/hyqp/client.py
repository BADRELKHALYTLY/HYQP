"""
Core HYQP client — handles TCP connection, protocol framing, QoS handshakes,
and asynchronous message dispatch via callbacks.

Zero external dependencies.  Works on CPython 3.8+ and Raspberry Pi.
"""

import json
import socket
import threading
import logging
import time
from typing import Any, Callable, Dict, Optional, Union

logger = logging.getLogger("hyqp")

# Type alias for the user-facing message callback
MessageCallback = Callable[[str, Any, Optional[int], Optional[int]], None]
# signature: callback(topic, payload, qos, message_id)


class HYQPError(Exception):
    """Raised when the broker returns an ERROR response."""


class HYQPClient:
    """Full-featured HYQP client with publisher and subscriber capabilities.

    Parameters
    ----------
    host : str
        Broker hostname or IP (default ``"localhost"``).
    port : int
        Broker TCP port (default ``4444``).
    client_id : str
        Unique client identifier sent with CONNECT.
    persistent : bool
        If *True* the broker preserves subscriptions across reconnects.
    on_message : callable, optional
        Default callback for incoming messages.
        Signature: ``(topic: str, payload, qos: int, msg_id: int | None) -> None``
    on_connect : callable, optional
        Called after a successful CONNECT.  Signature: ``(client, response: str) -> None``
    on_disconnect : callable, optional
        Called when the connection drops.  Signature: ``(client, reason: str) -> None``
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = 4444,
        client_id: str = "hyqp-python",
        persistent: bool = False,
        on_message: Optional[MessageCallback] = None,
        on_connect: Optional[Callable] = None,
        on_disconnect: Optional[Callable] = None,
    ):
        self.host = host
        self.port = port
        self.client_id = client_id
        self.persistent = persistent

        # Callbacks
        self._on_message = on_message
        self._on_connect = on_connect
        self._on_disconnect = on_disconnect

        # Per-topic callbacks override the default on_message
        self._topic_callbacks: Dict[str, MessageCallback] = {}

        # Internals
        self._sock: Optional[socket.socket] = None
        self._reader_thread: Optional[threading.Thread] = None
        self._connected = threading.Event()
        self._stop = threading.Event()
        self._lock = threading.Lock()  # guards _sock writes
        self._response_queue: list = []
        self._response_event = threading.Event()

    # ------------------------------------------------------------------
    # Connection lifecycle
    # ------------------------------------------------------------------

    def connect(self, timeout: float = 5.0) -> str:
        """Open TCP connection and send CONNECT.  Returns the broker's OK line."""
        self._sock = socket.create_connection((self.host, self.port), timeout=timeout)
        self._sock.settimeout(None)  # blocking reads in the reader thread
        self._stop.clear()

        # Start background reader BEFORE sending CONNECT
        self._reader_thread = threading.Thread(
            target=self._read_loop, daemon=True, name="hyqp-reader"
        )
        self._reader_thread.start()

        cmd = f"CONNECT {self.client_id}"
        if self.persistent:
            cmd += " PERSISTENT"
        resp = self._send_and_wait(cmd, timeout=timeout)
        self._connected.set()

        if self._on_connect:
            self._on_connect(self, resp)
        return resp

    def disconnect(self) -> str:
        """Send DISCONNECT and close the socket."""
        try:
            resp = self._send_and_wait("DISCONNECT", timeout=3.0)
        except Exception:
            resp = ""
        self._cleanup("client disconnect")
        return resp

    def reconnect(self, timeout: float = 5.0) -> str:
        """Close existing connection (if any) and reconnect."""
        self._cleanup("reconnect")
        return self.connect(timeout=timeout)

    @property
    def is_connected(self) -> bool:
        return self._connected.is_set() and not self._stop.is_set()

    # ------------------------------------------------------------------
    # Topic management
    # ------------------------------------------------------------------

    def create_topic(
        self, topic: str, schema: Optional[Dict] = None, timeout: float = 5.0
    ) -> str:
        """CREATE a topic, optionally with a JSON Schema.

        Returns the broker OK response or raises HYQPError.
        """
        cmd = f"CREATE {topic}"
        if schema is not None:
            cmd += " " + json.dumps(schema, separators=(",", ":"))
        return self._send_and_wait(cmd, timeout=timeout)

    # ------------------------------------------------------------------
    # Publish
    # ------------------------------------------------------------------

    def publish(
        self,
        topic: str,
        payload: Any,
        qos: int = 0,
        retain: bool = False,
        timeout: float = 5.0,
    ) -> str:
        """Publish a message.

        Parameters
        ----------
        topic : str
            Destination topic (may contain wildcards / regex).
        payload : dict | str | int | float | list
            Message body.  Dicts and lists are JSON-serialized automatically.
        qos : int
            Quality of service (0, 1, or 2).
        retain : bool
            If *True*, the broker stores the message for future subscribers.
        """
        parts = ["PUBLISH"]
        if retain:
            parts.append("RETAIN")
        if qos > 0:
            parts.append(f"QOS{qos}")
        parts.append(topic)

        if isinstance(payload, (dict, list)):
            parts.append(json.dumps(payload, separators=(",", ":")))
        else:
            parts.append(str(payload))

        return self._send_and_wait(" ".join(parts), timeout=timeout)

    # ------------------------------------------------------------------
    # Subscribe / Unsubscribe
    # ------------------------------------------------------------------

    def subscribe(
        self,
        topic: str,
        callback: Optional[MessageCallback] = None,
        filter: Optional[Dict] = None,
        timeout: float = 5.0,
    ) -> str:
        """Subscribe to a topic (supports wildcards and regex).

        Parameters
        ----------
        topic : str
            Topic filter (e.g. ``"sensors/+"`` or ``"factory/regex('line-[0-9]+')/temp"``).
        callback : callable, optional
            Per-topic message handler.  Falls back to the client-level ``on_message``.
        filter : dict, optional
            Payload predicate — e.g. ``{"value": {"$gt": 30, "$lt": 80}}``.
        """
        cmd = f"SUBSCRIBE {topic}"
        if filter is not None:
            cmd += " FILTER " + json.dumps(filter, separators=(",", ":"))

        if callback is not None:
            self._topic_callbacks[topic] = callback

        return self._send_and_wait(cmd, timeout=timeout)

    def unsubscribe(self, topic: str, timeout: float = 5.0) -> str:
        """Unsubscribe from a topic."""
        self._topic_callbacks.pop(topic, None)
        return self._send_and_wait(f"UNSUBSCRIBE {topic}", timeout=timeout)

    # ------------------------------------------------------------------
    # Blocking event loop (useful for pure-subscriber scripts)
    # ------------------------------------------------------------------

    def loop_forever(self):
        """Block the calling thread until the connection drops or stop() is called."""
        try:
            while not self._stop.is_set():
                self._stop.wait(timeout=1.0)
        except KeyboardInterrupt:
            pass
        finally:
            self.disconnect()

    def loop_start(self):
        """Non-blocking: the reader thread is already running after connect()."""
        pass  # reader thread starts in connect()

    def stop(self):
        """Signal loop_forever() to exit."""
        self._stop.set()

    # ------------------------------------------------------------------
    # Internal: send + wait for OK/ERROR
    # ------------------------------------------------------------------

    def _send_raw(self, line: str):
        with self._lock:
            self._sock.sendall((line + "\n").encode("utf-8"))

    def _send_and_wait(self, cmd: str, timeout: float = 5.0) -> str:
        self._response_event.clear()
        self._response_queue.clear()
        self._send_raw(cmd)

        if not self._response_event.wait(timeout=timeout):
            raise TimeoutError(f"No broker response within {timeout}s for: {cmd}")

        resp = self._response_queue.pop(0) if self._response_queue else ""
        if resp.startswith("ERROR"):
            raise HYQPError(resp)
        return resp

    # ------------------------------------------------------------------
    # Internal: background reader thread
    # ------------------------------------------------------------------

    def _read_loop(self):
        buf = b""
        try:
            while not self._stop.is_set():
                chunk = self._sock.recv(4096)
                if not chunk:
                    break
                buf += chunk
                while b"\n" in buf:
                    line_bytes, buf = buf.split(b"\n", 1)
                    line = line_bytes.decode("utf-8").strip()
                    if not line:
                        continue
                    self._dispatch(line)
        except OSError:
            pass
        finally:
            self._connected.clear()
            if self._on_disconnect:
                try:
                    self._on_disconnect(self, "connection closed")
                except Exception:
                    pass

    def _dispatch(self, line: str):
        """Route an incoming broker line to the right handler."""

        if line.startswith("OK") or line.startswith("ERROR"):
            self._response_queue.append(line)
            self._response_event.set()
            return

        if line.startswith("MESSAGE"):
            self._handle_message(line)
            return

        if line.startswith("PUBREL"):
            # QoS 2 step 3 — broker asks us to complete
            parts = line.split()
            if len(parts) >= 2:
                msg_id = parts[1]
                self._send_raw(f"PUBCOMP {msg_id}")
            return

        # Anything else (unexpected) — log it
        logger.debug("unhandled broker line: %s", line)

    def _handle_message(self, line: str):
        """Parse MESSAGE lines and fire the appropriate callback.

        Formats:
            MESSAGE <topic> <payload>                       (QoS 0)
            MESSAGE <msgId> QOS1 <topic> <payload>          (QoS 1)
            MESSAGE <msgId> QOS2 <topic> <payload>          (QoS 2)
        """
        parts = line.split(None, 1)  # ["MESSAGE", rest]
        if len(parts) < 2:
            return
        rest = parts[1]

        msg_id = None
        qos = 0

        # Check if next token is a numeric message ID (QoS 1 or 2)
        tokens = rest.split(None, 1)
        if len(tokens) >= 2 and tokens[0].isdigit():
            msg_id = int(tokens[0])
            rest = tokens[1]

            # Next token should be QOS1 or QOS2
            tokens2 = rest.split(None, 1)
            if tokens2[0].upper().startswith("QOS"):
                qos = int(tokens2[0][-1])
                rest = tokens2[1] if len(tokens2) > 1 else ""

        # Now rest = "<topic> <payload>"
        # Topic is the first token, payload is the remainder
        topic_and_payload = rest.split(None, 1)
        topic = topic_and_payload[0]
        raw_payload = topic_and_payload[1] if len(topic_and_payload) > 1 else ""

        # Try to parse payload as JSON
        try:
            payload = json.loads(raw_payload)
        except (json.JSONDecodeError, ValueError):
            payload = raw_payload

        # QoS acknowledgments
        if qos == 1 and msg_id is not None:
            self._send_raw(f"PUBACK {msg_id}")
        elif qos == 2 and msg_id is not None:
            self._send_raw(f"PUBREC {msg_id}")

        # Fire callback
        cb = self._find_callback(topic)
        if cb:
            try:
                cb(topic, payload, qos, msg_id)
            except Exception as exc:
                logger.error("callback error for %s: %s", topic, exc)

    def _find_callback(self, topic: str) -> Optional[MessageCallback]:
        """Find the best matching callback for a concrete topic."""
        # Exact match first
        if topic in self._topic_callbacks:
            return self._topic_callbacks[topic]

        # Try wildcard / pattern callbacks
        for pattern, cb in self._topic_callbacks.items():
            if self._topic_matches(pattern, topic):
                return cb

        # Fall back to default
        return self._on_message

    @staticmethod
    def _topic_matches(pattern: str, topic: str) -> bool:
        """Minimal client-side wildcard matching for callback routing."""
        pat_parts = pattern.split("/")
        top_parts = topic.split("/")

        pi = 0
        ti = 0
        while pi < len(pat_parts) and ti < len(top_parts):
            seg = pat_parts[pi]
            if seg == "#":
                return True
            if seg == "+" or seg == top_parts[ti]:
                pi += 1
                ti += 1
                continue
            return False

        return pi == len(pat_parts) and ti == len(top_parts)

    # ------------------------------------------------------------------
    # Cleanup
    # ------------------------------------------------------------------

    def _cleanup(self, reason: str = ""):
        self._stop.set()
        self._connected.clear()
        if self._sock:
            try:
                self._sock.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *exc):
        self.disconnect()

    def __del__(self):
        self._cleanup("garbage collected")
