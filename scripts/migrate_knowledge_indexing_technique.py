#!/usr/bin/env python3
"""Migrate legacy ECONOMY knowledge datasets to vector-backed HIGH_QUALITY.

Connection values are read from DB_HOST, DB_PORT, DB_NAME, DB_USER and
DB_PASSWORD. The password is intentionally not accepted as a command-line
argument. The script uses psql directly, or psql from a running Docker
container when the host has no client installed.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys


def required(name: str, default: str | None = None) -> str:
    value = os.environ.get(name, default)
    if not value:
        raise SystemExit(f"Missing required environment variable: {name}")
    return value


def psql_command() -> tuple[list[str], dict[str, str]]:
    host = required("DB_HOST", "localhost")
    port = required("DB_PORT", "5432")
    database = required("DB_NAME")
    user = required("DB_USER", "postgres")
    password = required("DB_PASSWORD")
    environment = dict(os.environ, PGPASSWORD=password)
    arguments = ["-X", "-v", "ON_ERROR_STOP=1", "-h", host, "-p", port, "-U", user, "-d", database]
    if shutil.which("psql"):
        return ["psql", *arguments], environment
    container = os.environ.get("PSQL_DOCKER_CONTAINER", "yudao-postgres")
    return ["docker", "exec", "-i", "-e", "PGPASSWORD", container, "psql", *arguments], environment


def main() -> int:
    command, environment = psql_command()
    sql = """
BEGIN;
SELECT count(*) AS legacy_rows_before
FROM goodle_dataset WHERE indexing_technique = 'ECONOMY';
UPDATE goodle_dataset
SET indexing_technique = 'HIGH_QUALITY'
WHERE indexing_technique = 'ECONOMY';
SELECT count(*) AS legacy_rows_after
FROM goodle_dataset WHERE indexing_technique = 'ECONOMY';
COMMIT;
"""
    completed = subprocess.run(command, input=sql, text=True, env=environment, check=False)
    return completed.returncode


if __name__ == "__main__":
    sys.exit(main())
