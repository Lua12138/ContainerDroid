#!/usr/bin/env python3
import argparse
import struct
import sys
import zlib
from pathlib import Path


PNG_SIGNATURE = b"\x89PNG\r\n\x1a\n"


def read_png(path):
    data = Path(path).read_bytes()
    if data[:8] != PNG_SIGNATURE:
        raise ValueError(f"not a PNG file: {path}")

    pos = 8
    width = height = bit_depth = color_type = interlace = None
    idat = []
    while pos < len(data):
        if pos + 8 > len(data):
            raise ValueError(f"truncated PNG chunk header: {path}")
        length = struct.unpack(">I", data[pos:pos + 4])[0]
        pos += 4
        chunk_type = data[pos:pos + 4]
        pos += 4
        chunk = data[pos:pos + length]
        pos += length
        pos += 4

        if chunk_type == b"IHDR":
            width, height, bit_depth, color_type, compression, png_filter, interlace = (
                struct.unpack(">IIBBBBB", chunk)
            )
            if compression != 0 or png_filter != 0:
                raise ValueError(f"unsupported PNG compression/filter method: {path}")
        elif chunk_type == b"IDAT":
            idat.append(chunk)
        elif chunk_type == b"IEND":
            break

    if bit_depth != 8 or color_type not in (2, 6) or interlace != 0:
        raise ValueError(
            f"unsupported PNG format: {path}; need non-interlaced 8-bit RGB/RGBA"
        )

    bytes_per_pixel = 4 if color_type == 6 else 3
    stride = width * bytes_per_pixel
    raw = zlib.decompress(b"".join(idat))
    pixels = bytearray(height * stride)
    previous = bytearray(stride)
    raw_pos = 0
    out_pos = 0

    for _ in range(height):
        filter_type = raw[raw_pos]
        raw_pos += 1
        current = bytearray(raw[raw_pos:raw_pos + stride])
        raw_pos += stride
        unfilter_scanline(current, previous, bytes_per_pixel, filter_type)
        pixels[out_pos:out_pos + stride] = current
        out_pos += stride
        previous = current

    return width, height, bytes_per_pixel, pixels


def unfilter_scanline(current, previous, bytes_per_pixel, filter_type):
    if filter_type == 0:
        return

    for index in range(len(current)):
        left = current[index - bytes_per_pixel] if index >= bytes_per_pixel else 0
        up = previous[index]
        upper_left = previous[index - bytes_per_pixel] if index >= bytes_per_pixel else 0

        if filter_type == 1:
            predictor = left
        elif filter_type == 2:
            predictor = up
        elif filter_type == 3:
            predictor = (left + up) // 2
        elif filter_type == 4:
            predictor = paeth(left, up, upper_left)
        else:
            raise ValueError(f"unsupported PNG filter type: {filter_type}")

        current[index] = (current[index] + predictor) & 0xFF


def paeth(left, up, upper_left):
    estimate = left + up - upper_left
    left_distance = abs(estimate - left)
    up_distance = abs(estimate - up)
    upper_left_distance = abs(estimate - upper_left)
    if left_distance <= up_distance and left_distance <= upper_left_distance:
        return left
    if up_distance <= upper_left_distance:
        return up
    return upper_left


def compare(left_path, right_path, ignore_top, high_delta, major_delta):
    left_width, left_height, left_bpp, left_pixels = read_png(left_path)
    right_width, right_height, right_bpp, right_pixels = read_png(right_path)

    if (left_width, left_height, left_bpp) != (right_width, right_height, right_bpp):
        return {
            "matched": False,
            "reason": "dimension_or_format_mismatch",
            "left_size": f"{left_width}x{left_height}x{left_bpp}",
            "right_size": f"{right_width}x{right_height}x{right_bpp}",
        }

    ignored_rows = min(max(ignore_top, 0), left_height)
    start = ignored_rows * left_width * left_bpp
    total_pixels = max((left_height - ignored_rows) * left_width, 1)
    changed_pixels = 0
    high_delta_pixels = 0
    major_delta_pixels = 0
    max_delta = 0
    absolute_delta_sum = 0

    for index in range(start, len(left_pixels), left_bpp):
        red_delta = abs(left_pixels[index] - right_pixels[index])
        green_delta = abs(left_pixels[index + 1] - right_pixels[index + 1])
        blue_delta = abs(left_pixels[index + 2] - right_pixels[index + 2])
        pixel_delta = max(red_delta, green_delta, blue_delta)
        absolute_delta_sum += red_delta + green_delta + blue_delta
        max_delta = max(max_delta, pixel_delta)
        if pixel_delta > 0:
            changed_pixels += 1
        if pixel_delta > high_delta:
            high_delta_pixels += 1
        if pixel_delta > major_delta:
            major_delta_pixels += 1

    return {
        "matched": True,
        "reason": "metrics",
        "width": left_width,
        "height": left_height,
        "ignored_top_rows": ignored_rows,
        "compared_pixels": total_pixels,
        "changed_percent": changed_pixels * 100.0 / total_pixels,
        "high_delta_percent": high_delta_pixels * 100.0 / total_pixels,
        "major_delta_percent": major_delta_pixels * 100.0 / total_pixels,
        "average_abs_delta": absolute_delta_sum / (total_pixels * 3.0),
        "max_delta": max_delta,
    }


def parse_args():
    parser = argparse.ArgumentParser(
        description="Compare Android screenshots by content, not byte identity."
    )
    parser.add_argument("left")
    parser.add_argument("right")
    parser.add_argument("--ignore-top", type=int, default=48)
    parser.add_argument("--high-delta", type=int, default=8)
    parser.add_argument("--major-delta", type=int, default=128)
    parser.add_argument("--max-average-abs", type=float, default=2.0)
    parser.add_argument("--max-high-delta-percent", type=float, default=3.0)
    parser.add_argument("--max-major-delta-percent", type=float, default=0.5)
    return parser.parse_args()


def main():
    args = parse_args()
    metrics = compare(
        args.left,
        args.right,
        args.ignore_top,
        args.high_delta,
        args.major_delta,
    )

    for key in sorted(metrics):
        print(f"{key}={metrics[key]}")

    if not metrics["matched"]:
        print("screenshot_content_status=failed")
        return 1

    if metrics["average_abs_delta"] > args.max_average_abs:
        print("screenshot_content_status=failed_average_abs_delta")
        return 1
    if metrics["high_delta_percent"] > args.max_high_delta_percent:
        print("screenshot_content_status=failed_high_delta_percent")
        return 1
    if metrics["major_delta_percent"] > args.max_major_delta_percent:
        print("screenshot_content_status=failed_major_delta_percent")
        return 1

    print("screenshot_content_status=matched")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(f"screenshot_content_status=error: {exc}", file=sys.stderr)
        raise
