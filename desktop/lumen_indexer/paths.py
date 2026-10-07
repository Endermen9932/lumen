"""Where the app keeps its data (XDG compliant, overridable for tests)."""
from __future__ import annotations

import os
from pathlib import Path


def data_dir() -> Path:
    override = os.environ.get("LUMEN_INDEXER_HOME")
    base = Path(override) if override else Path(os.environ.get("XDG_DATA_HOME") or Path.home() / ".local" / "share") / "lumen-indexer"
    base.mkdir(parents=True, exist_ok=True)
    return base


def models_dir() -> Path:
    d = data_dir() / "models"
    d.mkdir(parents=True, exist_ok=True)
    return d


def cache_db() -> Path:
    return data_dir() / "index.db"
