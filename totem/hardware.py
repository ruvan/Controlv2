"""Relay board drivers (NCD ProXR protocol over serial, plus a simulator)."""

from __future__ import annotations

import logging
import time
from typing import Protocol

log = logging.getLogger(__name__)

NUM_SENSOR_CHANNELS = 13
_CMD = 254
_SET_BANK = 140
_READ_ALL_AD = 204
_SENSOR_REPLY_LEN = 1 + 2 * NUM_SENSOR_CHANNELS  # one header byte, then LSB/MSB pairs


class RelayBoard(Protocol):
    def write_bank(self, bank: int, value: int) -> None:
        """Set all relays of a bank. bank is 0-based; value bit i drives relay i."""

    def read_sensors(self) -> list[int] | None:
        """Read all analog inputs, or None if the board did not answer properly."""

    def close(self) -> None: ...


def decode_sensor_reply(data: bytes) -> list[int] | None:
    if len(data) != _SENSOR_REPLY_LEN:
        return None
    return [data[1 + 2 * i] | (data[2 + 2 * i] << 8) for i in range(NUM_SENSOR_CHANNELS)]


class SerialRelayBoard:
    def __init__(self, port: str, baud: int):
        import serial  # imported lazily so the simulator works without pyserial

        self._serial = serial.Serial(
            port,
            baudrate=baud,
            bytesize=serial.EIGHTBITS,
            stopbits=serial.STOPBITS_ONE,
            parity=serial.PARITY_NONE,
            timeout=1.0,
            write_timeout=1.0,
        )

    def write_bank(self, bank: int, value: int) -> None:
        # ProXR banks are numbered from 1. The pauses keep the board from dropping commands.
        time.sleep(0.010)
        self._serial.write(bytes((_CMD, _SET_BANK, value, bank + 1)))
        time.sleep(0.008)

    def read_sensors(self) -> list[int] | None:
        self._serial.reset_input_buffer()
        self._serial.write(bytes((_CMD, _READ_ALL_AD)))
        data = self._serial.read(_SENSOR_REPLY_LEN)
        values = decode_sensor_reply(data)
        if values is None:
            log.warning("short sensor reply (%d of %d bytes)", len(data), _SENSOR_REPLY_LEN)
        return values

    def close(self) -> None:
        self._serial.close()


class SimulatedRelayBoard:
    """Stands in for the board when no hardware is attached (and in tests)."""

    def __init__(self) -> None:
        self.banks = [0] * 19
        self.sensors = [0] * NUM_SENSOR_CHANNELS
        self.writes = 0

    def write_bank(self, bank: int, value: int) -> None:
        self.banks[bank] = value
        self.writes += 1

    def read_sensors(self) -> list[int] | None:
        return list(self.sensors)

    def close(self) -> None:
        pass


def open_board(port: str, baud: int) -> RelayBoard:
    if port.lower() == "sim":
        log.info("using simulated relay board")
        return SimulatedRelayBoard()
    log.info("opening relay board on %s at %d baud", port, baud)
    return SerialRelayBoard(port, baud)
