#!/usr/bin/env python3
import argparse
from pathlib import Path


def read_lines(path: Path) -> list[str]:
    return path.read_text(encoding="utf-8", errors="replace").splitlines()


def find_overlap(prev: list[str], cur: list[str]) -> int:
    """
    Find largest n where prev[-n:] == cur[:n].
    Return n.
    """
    max_n = min(len(prev), len(cur))
    for n in range(max_n, 0, -1):
        if prev[-n:] == cur[:n]:
            return n
    return 0


def merge_logs(input_dir: Path, output_file: Path, pattern: str) -> None:
    files = sorted(input_dir.glob(pattern))

    if not files:
        raise SystemExit(f"No files matched: {input_dir}/{pattern}")

    merged: list[str] = []

    for i, file in enumerate(files):
        cur = read_lines(file)

        if i == 0:
            merged.extend(cur)
            continue

        overlap = find_overlap(merged, cur)

        if overlap > 0:
            merged.extend(cur[overlap:])
        else:
            merged.append(f"===== overlap lost before {file.name} =====")
            merged.extend(cur)

    output_file.write_text(
        "\n".join(merged) + "\n",
        encoding="utf-8",
        errors="replace",
    )

    print(f"merged {len(files)} files")
    print(f"output: {output_file}")
    print(f"lines: {len(merged)}")


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Merge binder transaction_log snapshots and remove overlapping duplicate parts."
    )
    parser.add_argument("input_dir", help="Directory containing 0001.log, 0002.log, ...")
    parser.add_argument(
        "-o",
        "--output",
        default="binder_merged.log",
        help="Output merged log file",
    )
    parser.add_argument(
        "-p",
        "--pattern",
        default="*.log",
        help="Input filename pattern, default: *.log",
    )

    args = parser.parse_args()

    merge_logs(
        input_dir=Path(args.input_dir),
        output_file=Path(args.output),
        pattern=args.pattern,
    )


if __name__ == "__main__":
    main()