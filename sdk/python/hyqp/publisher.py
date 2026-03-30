"""
Convenience wrapper for publish-only workloads.

    from hyqp import Publisher

    pub = Publisher(host="192.168.1.10", port=4444, client_id="rpi-sensor")
    pub.connect()
    pub.create_topic("factory/line-1/temp", schema={
        "type": "object",
        "required": ["value"],
        "properties": {"value": {"type": "number", "minimum": -50, "maximum": 150}}
    })
    pub.publish("factory/line-1/temp", {"value": 23.4, "unit": "celsius"})
    pub.disconnect()
"""

from hyqp.client import HYQPClient, HYQPError
from typing import Any, Dict, Optional


class Publisher:
    """Thin publisher facade around :class:`HYQPClient`.

    All parameters mirror those of :class:`HYQPClient`.
    """

    def __init__(
        self,
        host: str = "localhost",
        port: int = 4444,
        client_id: str = "hyqp-pub",
        persistent: bool = False,
    ):
        self._client = HYQPClient(
            host=host, port=port, client_id=client_id, persistent=persistent
        )

    def connect(self, timeout: float = 5.0) -> str:
        return self._client.connect(timeout=timeout)

    def disconnect(self) -> str:
        return self._client.disconnect()

    def create_topic(
        self, topic: str, schema: Optional[Dict] = None, timeout: float = 5.0
    ) -> str:
        return self._client.create_topic(topic, schema=schema, timeout=timeout)

    def publish(
        self,
        topic: str,
        payload: Any,
        qos: int = 0,
        retain: bool = False,
        timeout: float = 5.0,
    ) -> str:
        return self._client.publish(
            topic, payload, qos=qos, retain=retain, timeout=timeout
        )

    @property
    def is_connected(self) -> bool:
        return self._client.is_connected

    def __enter__(self):
        self.connect()
        return self

    def __exit__(self, *exc):
        self.disconnect()
