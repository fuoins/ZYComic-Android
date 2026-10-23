#!/usr/bin/env python3
"""Compile and run the real Manwa SpeedTester two-stage runtime contract offline."""
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
HARNESS = ROOT / "tools/offline-tests/manwa-two-stage/run-tests.py"


def main() -> None:
    subprocess.run([sys.executable, str(HARNESS)], cwd=ROOT, check=True)


if __name__ == "__main__":
    main()
