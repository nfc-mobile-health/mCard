#!/usr/bin/env python3
"""
Phase 1 mCard HCE smoke test for macOS + PC/SC USB NFC reader.

Install dependency:
  python3 -m pip install pyscard

Usage:
  python3 tools/phase1_hce_test.py
  python3 tools/phase1_hce_test.py --reader "ACS ACR122U"

Before running:
  1. Install and open the mCard app on the Android phone.
  2. Enter basic info in the app.
  3. Hold the back of the phone on the reader.
"""

from __future__ import annotations

import argparse
import sys
import time
from dataclasses import dataclass
from typing import Iterable

try:
    from smartcard.CardConnection import CardConnection
    from smartcard.Exceptions import CardConnectionException, NoCardException
    from smartcard.System import readers
except ImportError:
    print("Missing dependency: pyscard")
    print("Install it with: python3 -m pip install pyscard")
    sys.exit(2)


AID = "F01234567821"
SELECT = "00A4040006" + AID
GET_INFO = "8000000000"
GET_BASIC_INFO = "8002000000"

SW_SUCCESS = "9000"
SW_DATA_NOT_FOUND = "6A88"


@dataclass(frozen=True)
class ApduResult:
    data_hex: str
    sw_hex: str

    @property
    def full_hex(self) -> str:
        return self.data_hex + self.sw_hex


def hex_to_list(value: str) -> list[int]:
    clean = "".join(value.split())
    if len(clean) % 2 != 0:
        raise ValueError(f"Odd-length hex: {value}")
    return [int(clean[i : i + 2], 16) for i in range(0, len(clean), 2)]


def list_to_hex(values: Iterable[int]) -> str:
    return "".join(f"{value & 0xFF:02X}" for value in values)


def transmit(connection: CardConnection, name: str, apdu_hex: str) -> ApduResult:
    data, sw1, sw2 = connection.transmit(hex_to_list(apdu_hex))
    result = ApduResult(data_hex=list_to_hex(data), sw_hex=f"{sw1:02X}{sw2:02X}")
    print(f"{name}")
    print(f"  > {apdu_hex}")
    print(f"  < {result.full_hex}")
    return result


def choose_reader(name_filter: str | None):
    available = readers()
    if not available:
        raise RuntimeError("No PC/SC readers found. Check the USB reader connection.")

    if name_filter:
        matches = [reader for reader in available if name_filter.lower() in str(reader).lower()]
        if not matches:
            names = "\n  ".join(str(reader) for reader in available)
            raise RuntimeError(f"No reader matched {name_filter!r}. Available readers:\n  {names}")
        return matches[0]

    return available[0]


def connect_when_present(reader, timeout_seconds: int) -> CardConnection:
    deadline = time.time() + timeout_seconds
    last_error = None
    while time.time() < deadline:
        try:
            connection = reader.createConnection()
            connection.connect()
            return connection
        except (NoCardException, CardConnectionException) as exc:
            last_error = exc
            time.sleep(0.5)
    raise TimeoutError(f"No phone/card detected within {timeout_seconds}s. Last error: {last_error}")


def expect_status(result: ApduResult, expected: set[str], name: str) -> None:
    if result.sw_hex not in expected:
        raise AssertionError(f"{name} returned SW {result.sw_hex}, expected one of {sorted(expected)}")


def validate_get_info(result: ApduResult) -> None:
    expect_status(result, {SW_SUCCESS}, "GET_INFO")
    if len(result.data_hex) != 22:
        raise AssertionError(f"GET_INFO data length was {len(result.data_hex) // 2}, expected 11")

    api_version = result.data_hex[0:2]
    record_format = result.data_hex[2:4]
    record_count = result.data_hex[4:8]
    max_count = result.data_hex[8:12]
    ring_size = result.data_hex[12:16]
    free_bytes = result.data_hex[16:20]
    basic_written = result.data_hex[20:22]

    checks = {
        "api version": (api_version, "01"),
        "record format": (record_format, "01"),
        "record count": (record_count, "0000"),
        "max count": (max_count, "0014"),
        "ring size": (ring_size, "1000"),
        "free bytes": (free_bytes, "1000"),
    }
    for label, (actual, expected) in checks.items():
        if actual != expected:
            raise AssertionError(f"GET_INFO {label} was {actual}, expected {expected}")

    if basic_written not in {"00", "01"}:
        raise AssertionError(f"GET_INFO basic-info flag was {basic_written}, expected 00 or 01")


def validate_basic_info(result: ApduResult) -> None:
    if result.sw_hex == SW_DATA_NOT_FOUND:
        print("  Basic info is not written yet. Enter setup info in the app, then run this again.")
        return
    expect_status(result, {SW_SUCCESS}, "GET_BASIC_INFO")
    data = result.data_hex
    if not data.startswith("10"):
        raise AssertionError("GET_BASIC_INFO block does not start with format byte 10")
    if len(data) > 116:
        raise AssertionError(f"GET_BASIC_INFO data length was {len(data) // 2}, expected at most 58")
    print(f"  Basic info block: {data}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Test Phase 1 mCard HCE APDUs through a PC/SC reader.")
    parser.add_argument("--reader", help="Substring of the reader name to use, for example 'ACS ACR122U'.")
    parser.add_argument("--timeout", type=int, default=30, help="Seconds to wait for the phone/card.")
    args = parser.parse_args()

    try:
        reader = choose_reader(args.reader)
        print(f"Using reader: {reader}")
        print("Hold the Android phone on the reader now.")
        connection = connect_when_present(reader, args.timeout)

        select = transmit(connection, "SELECT mCard AID", SELECT)
        expect_status(select, {SW_SUCCESS}, "SELECT")

        info = transmit(connection, "GET_INFO", GET_INFO)
        validate_get_info(info)

        basic = transmit(connection, "GET_BASIC_INFO", GET_BASIC_INFO)
        validate_basic_info(basic)

        print("Phase 1 HCE smoke test completed.")
        return 0
    except Exception as exc:
        print(f"FAILED: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
