"""
Convenience wrapper for subscribe-only workloads.

    from hyqp import Subscriber

    def on_temp(topic, payload, qos, msg_id):
        print(f"[{topic}] value={payload['value']}")

    sub = Subscriber(host="192.168.1.10", port=4444, client_id="rpi-dashboard")
    sub.connect()
    sub.subscribe("sensors/+", callback=on_temp, filter={"value": {"$gt": 25}})
    sub.loop_forever()   # blocks until Ctrl-C
"""

from hyqp.client import HYQPClient, HYQPError, MessageCallback
from typing import Callable, Dict, Optional


class Subscriber:
    """Thin subscriber facade around :class:`HYQPClient`.

    All parameters mirror those of :class:`HYQPClient`.
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = 4444,
        client_id: str = "hyqp-sub",
        persistent: bool = False,
        on_message: Optional[MessageCallback] = None,
        on_connect: Optional[Callable] = None,
        on_disconnect: Optional[Callable] = None,
    ):
        self._client = HYQPClient(
            host=host,
            port=port,
            client_id=client_id,
            persistent=persistent,
            on_message=on_message,
            on_connect=on_connect,
            on_disconnect=on_disconnect,
        )

    def connect(self, timeout: float = 5.0) -> str:
        return self._client.connect(timeout=timeout)

    def disconnect(self) -> str:
        return self._client.disconnect()

    def subscribe(
        self,
        topic: str,
        callback: Optional[MessageCallback] = None,
        filter: Optional[Dict] = None,
        timeout: float = 5.0,
    ) -> str:
        return self._client.subscribe(
            topic, callback=callback, filter=filter, timeout=timeout
        )

    def unsubscribe(self, topic: str, timeout: float = 5.0) -> str:
        return self._client.unsubscribe(topic, timeout=timeout)

    def loop_forever(self):
        self._client.loop_forever()

    def stop(self):
        self._client.stop()

    @property
    def is_connected(self) -> bool:
        return self._client.is_connected

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *exc):
        self.disconnect()
