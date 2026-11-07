#!/usr/bin/env python3
"""Verify ELF LOAD alignment for every native library bundled in APKs."""

from __future__ import annotations

import argparse
import struct
import sys
import zipfile
from pathlib import Path

PAGE_SIZE_16_KIB = 16 * 1024


def elf_load_alignments(data: bytes) -> tuple[int, list[int]]:
    if len(data) < 64 or data[:4] != b"\x7fELF":
        raise ValueError("not an ELF file")
    elf_class = data[4]
    byte_order = {1: "<", 2: ">"}.get(data[5])
    if byte_order is None:
        raise ValueError("unknown ELF byte order")

    if elf_class == 1:
        header_offset, header_format, align_index = 28, "IIIIIIII", 7
        program_offset, entry_size, entry_count = struct.unpack_from(
            byte_order + "I10xHH", data, header_offset
        )
    elif elf_class == 2:
        header_offset, header_format, align_index = 32, "IIQQQQQQ", 7
        program_offset, entry_size, entry_count = struct.unpack_from(
            byte_order + "Q14xHH", data, header_offset
        )
    else:
        raise ValueError(f"unknown ELF class {elf_class}")

    expected_size = struct.calcsize(byte_order + header_format)
    if entry_size < expected_size:
        raise ValueError("truncated program header")
    alignments: list[int] = []
    for index in range(entry_count):
        offset = program_offset + index * entry_size
        if offset + expected_size > len(data):
            raise ValueError("program header outside file")
        header = struct.unpack_from(byte_order + header_format, data, offset)
        if header[0] == 1:  # PT_LOAD
            alignments.append(header[align_index])
    if not alignments:
        raise ValueError("no LOAD segments")
    return elf_class, alignments


def check_apk(path: Path) -> tuple[int, list[str]]:
    checked = 0
    failures: list[str] = []
    with zipfile.ZipFile(path) as archive:
        libraries = sorted(
            name for name in archive.namelist() if name.startswith("lib/") and name.endswith(".so")
        )
        for name in libraries:
            checked += 1
            try:
                elf_class, alignments = elf_load_alignments(archive.read(name))
            except (OSError, ValueError, struct.error) as error:
                failures.append(f"{path}:{name}: {error}")
                continue
            minimum = min(alignments)
            if elf_class == 2 and minimum < PAGE_SIZE_16_KIB:
                failures.append(
                    f"{path}:{name}: 64-bit LOAD alignment is {minimum} bytes, expected at least 16384"
                )
    return checked, failures


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("apks", nargs="+", type=Path)
    args = parser.parse_args()

    total = 0
    failures: list[str] = []
    for apk in args.apks:
        checked, apk_failures = check_apk(apk)
        total += checked
        failures.extend(apk_failures)
        print(f"{apk}: inspected {checked} native libraries")
    if total == 0:
        failures.append("no native libraries found")
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"Verified {total} native libraries; every 64-bit LOAD segment is 16 KiB aligned.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
