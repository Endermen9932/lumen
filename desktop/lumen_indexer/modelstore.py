"""Downloads and keeps the model files (resumable, like ModelManager.kt on Android)."""
from __future__ import annotations

import threading
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Callable

from . import __version__
from .catalog import AiModel
from .paths import models_dir

ProgressCb = Callable[[int, int], None]


class Cancelled(Exception):
    pass


class ModelStore:
    def __init__(self, root: Path | None = None):
        self.root = root or models_dir()

    def dir(self, model: AiModel) -> Path:
        return self.root / model.id

    def path(self, model: AiModel, file=None) -> Path:
        return self.dir(model) / (file or model.vision_file).name

    def is_installed(self, model: AiModel) -> bool:
        return all(self.path(model, f).is_file() and self.path(model, f).stat().st_size == f.size for f in model.files)

    def downloaded_bytes(self, model: AiModel) -> int:
        total = 0
        for f in model.files:
            done = self.path(model, f)
            part = done.with_name(done.name + ".part")
            if done.is_file():
                total += done.stat().st_size
            elif part.is_file():
                total += part.stat().st_size
        return total

    def delete(self, model: AiModel) -> None:
        d = self.dir(model)
        if d.is_dir():
            for p in d.iterdir():
                p.unlink(missing_ok=True)
            d.rmdir()

    def download(self, model: AiModel, on_progress: ProgressCb, cancel: threading.Event | None = None) -> None:
        """Downloads every missing file. Raises Cancelled / OSError."""
        d = self.dir(model)
        d.mkdir(parents=True, exist_ok=True)
        total = model.total_bytes
        base = 0
        for f in model.files:
            target = self.path(model, f)
            if target.is_file() and target.stat().st_size == f.size:
                base += f.size
                on_progress(base, total)
                continue
            part = target.with_name(target.name + ".part")
            attempt = 0
            while True:
                try:
                    _download_file(model.url(f), part, lambda done, b=base: on_progress(b + done, total), cancel)
                    break
                except (urllib.error.URLError, OSError, TimeoutError) as e:
                    if isinstance(e, urllib.error.HTTPError) and e.code in (401, 403, 404):
                        raise
                    attempt += 1
                    if attempt >= 5:
                        raise
                    time.sleep(2.0 * attempt)
            if part.stat().st_size != f.size:
                part.unlink(missing_ok=True)
                raise OSError(f"Unvollständige Datei {f.path}")
            part.replace(target)
            base += f.size


def _download_file(url: str, part: Path, on_progress: Callable[[int], None], cancel: threading.Event | None) -> None:
    existing = part.stat().st_size if part.exists() else 0
    headers = {"User-Agent": f"LumenIndexer/{__version__} (Linux)"}
    if existing:
        headers["Range"] = f"bytes={existing}-"
    try:
        resp = urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=60)
    except urllib.error.HTTPError as e:
        if e.code == 416:  # already complete
            return
        raise
    with resp:
        if resp.status == 200:
            existing = 0  # server ignored the range request
        done = existing
        last = 0.0
        with open(part, "ab" if existing else "wb") as out:
            while True:
                if cancel is not None and cancel.is_set():
                    raise Cancelled()
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
                done += len(chunk)
                now = time.monotonic()
                if now - last > 0.2:
                    last = now
                    on_progress(done)
        on_progress(done)
