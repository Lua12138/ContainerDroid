#!/usr/bin/env python3
import os
import struct
import subprocess
import tempfile
import zlib
from pathlib import Path


SCRIPT = Path(__file__).with_name("compare-screenshots.py")


def write_png(path, width, height, rows):
    raw = bytearray()
    for row in rows:
        raw.append(0)
        for r, g, b in row:
            raw.extend((r, g, b, 255))

    def chunk(kind, payload):
        return (
            struct.pack(">I", len(payload))
            + kind
            + payload
            + struct.pack(">I", zlib.crc32(kind + payload) & 0xFFFFFFFF)
        )

    path.write_bytes(
        b"\x89PNG\r\n\x1a\n"
        + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, 6, 0, 0, 0))
        + chunk(b"IDAT", zlib.compress(bytes(raw)))
        + chunk(b"IEND", b"")
    )


def run_compare(left, right, *args):
    env = dict(os.environ)
    return subprocess.run(
        [str(SCRIPT), str(left), str(right), *args],
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        env=env,
    )


def assert_success(result):
    if result.returncode != 0:
        raise AssertionError(result.stdout)


def assert_failure(result):
    if result.returncode == 0:
        raise AssertionError(result.stdout)


def main():
    with tempfile.TemporaryDirectory() as td:
        tmp = Path(td)
        base = tmp / "base.png"
        same = tmp / "same.png"
        status_changed = tmp / "status_changed.png"
        antialias = tmp / "antialias.png"
        content_changed = tmp / "content_changed.png"

        black = (0, 0, 0)
        red = (255, 0, 0)
        blue = (0, 0, 255)
        almost_black = (2, 2, 2)

        write_png(base, 4, 4, [
            [red, red, red, red],
            [black, black, black, black],
            [black, black, black, black],
            [black, black, black, black],
        ])
        write_png(same, 4, 4, [
            [red, red, red, red],
            [black, black, black, black],
            [black, black, black, black],
            [black, black, black, black],
        ])
        write_png(status_changed, 4, 4, [
            [blue, blue, blue, blue],
            [black, black, black, black],
            [black, black, black, black],
            [black, black, black, black],
        ])
        write_png(antialias, 4, 4, [
            [red, red, red, red],
            [almost_black, almost_black, almost_black, almost_black],
            [almost_black, almost_black, almost_black, almost_black],
            [almost_black, almost_black, almost_black, almost_black],
        ])
        write_png(content_changed, 4, 4, [
            [red, red, red, red],
            [black, black, black, black],
            [black, blue, blue, black],
            [black, black, black, black],
        ])

        assert_success(run_compare(base, same, "--ignore-top", "0"))
        assert_success(run_compare(base, status_changed, "--ignore-top", "1"))
        assert_success(run_compare(base, antialias, "--ignore-top", "0"))
        assert_failure(run_compare(base, content_changed, "--ignore-top", "1"))

    print("compare_screenshots_tests=passed")


if __name__ == "__main__":
    main()
