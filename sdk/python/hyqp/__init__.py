"""
HYQP Python SDK - Lightweight client for the Hybrid Query Protocol broker.

Zero dependencies beyond the Python standard library.
Runs on CPython 3.8+ and MicroPython (Raspberry Pi, ESP32).

Usage:
    from hyqp import HYQPClient, Publisher, Subscriber
"""

from hyqp.client import HYQPClient, HYQPError
from hyqp.publisher import Publisher
from hyqp.subscriber import Subscriber

__version__ = "1.0.0"
__all__ = ["HYQPClient", "Publisher", "Subscriber"]
