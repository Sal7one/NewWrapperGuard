#!/usr/bin/env python3
"""Explicit build gate. Set an absolute installed CLI and optional user-owned settings."""
import argparse
import os
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("project", type=Path)
args = parser.parse_args()
executable = Path(os.environ["WRAPPERGUARD_CLI"])
if not executable.is_absolute() or not executable.is_file():
    parser.error("WRAPPERGUARD_CLI must name an absolute installed CLI executable")
command = [str(executable), str(args.project.resolve(strict=True)), "--operation", "build", "--enforce", "--json"]
settings = os.environ.get("WRAPPERGUARD_PROVIDERS")
if settings:
    selected = Path(settings)
    if not selected.is_absolute():
        parser.error("WRAPPERGUARD_PROVIDERS must be an absolute user-owned file outside the project")
    command += ["--providers", str(selected)]
raise SystemExit(subprocess.run(command, check=False, timeout=330).returncode)
