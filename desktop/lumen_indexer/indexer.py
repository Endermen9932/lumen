"""The indexing run: scan a folder, embed every new/changed photo, keep results in the cache."""
from __future__ import annotations

import threading
import time
from collections import deque
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np

from .catalog import AiModel
from .engine import Engine, cpu_threads
from .exporter import encode_vector
from .imaging import load_prepared, normalize_batch, scan_images
from .modelstore import ModelStore
from .store import Store


@dataclass
class Progress:
    done: int = 0
    total: int = 0
    failed: int = 0
    per_second: float = 0.0
    eta_seconds: float = 0.0
    phase: str = "scan"  # scan | load | run
    found: int = 0
    device: str = ""


@dataclass
class Summary:
    total_images: int
    newly_indexed: int
    already_cached: int
    failed: int
    cancelled: bool
    seconds: float


ProgressCb = Callable[[Progress], None]


def store_key(model: AiModel, high_quality: bool) -> str:
    """Cache key: "Hoch" vectors are kept apart from standard ones (both are exported as model.id)."""
    return model.id + ("+tta" if high_quality else "")


def _flip(images: list[np.ndarray]) -> list[np.ndarray]:
    return [np.ascontiguousarray(im[:, ::-1]) for im in images]


def run_index(
    root: Path,
    model: AiModel,
    store: Store,
    models: ModelStore,
    on_progress: ProgressCb,
    cancel: threading.Event,
    threads: int | None = None,
    high_quality: bool = False,
    use_gpu: bool = True,
) -> Summary:
    """[high_quality]: every photo is also analysed mirrored and both vectors are averaged
    (test-time augmentation). Same model, same vector space as the phone – just a little more
    robust, at twice the computing time."""
    started = time.monotonic()
    root = Path(root).resolve()
    key = str(root)
    model_key = store_key(model, high_quality)
    prog = Progress()

    files = []
    for entry in scan_images(root):
        files.append(entry)
        if len(files) % 200 == 0:
            prog.found = len(files)
            on_progress(prog)
        if cancel.is_set():
            return Summary(len(files), 0, 0, 0, True, time.monotonic() - started)
    prog.found = len(files)

    known = store.known(key, model_key)
    store.prune(key, model_key, {f[0] for f in files})
    todo = [f for f in files if known.get(f[0], (None, None, False))[:2] != (f[2], f[3])]
    cached = len(files) - len(todo)
    prog.total = len(todo)
    if not todo:
        prog.phase = "run"
        on_progress(prog)
        return Summary(len(files), 0, cached, 0, False, time.monotonic() - started)

    prog.phase = "load"
    on_progress(prog)
    threads = threads or cpu_threads()
    engine = Engine(model, models, threads, use_gpu)
    prog.device = engine.device_label
    prog.phase = "run"
    on_progress(prog)

    batch_size = engine.batch_size
    decoders = max(2, min(8, threads // 2 if not engine.accelerated else threads))
    window = decoders * 2 + batch_size
    pending: deque[tuple[tuple, Future]] = deque()
    rows: list[tuple[str, int, int, bytes | None]] = []
    t0 = time.monotonic()
    last_ui = 0.0
    it = iter(todo)

    def flush() -> None:
        if rows:
            store.put_many(key, model_key, rows)
            rows.clear()

    def report(force: bool = False) -> None:
        nonlocal last_ui
        now = time.monotonic()
        if not force and now - last_ui < 0.3:
            return
        last_ui = now
        elapsed = max(now - t0, 1e-6)
        prog.per_second = prog.done / elapsed
        prog.eta_seconds = (prog.total - prog.done) / prog.per_second if prog.per_second > 0 else 0.0
        on_progress(prog)

    try:
        with ThreadPoolExecutor(max_workers=decoders, thread_name_prefix="decode") as pool:
            exhausted = False
            while True:
                while not exhausted and len(pending) < window:
                    entry = next(it, None)
                    if entry is None:
                        exhausted = True
                        break
                    pending.append((entry, pool.submit(load_prepared, entry[1], model)))
                if not pending or cancel.is_set():
                    break
                group = [pending.popleft() for _ in range(min(batch_size, len(pending)))]
                images, ok = [], []
                for entry, fut in group:
                    try:
                        images.append(fut.result())
                        ok.append(entry)
                    except Exception:
                        rows.append((entry[0], entry[2], entry[3], None))
                        prog.failed += 1
                        prog.done += 1
                if images:
                    vectors = engine.embed(normalize_batch(images, model))
                    if high_quality:
                        vectors = vectors + engine.embed(normalize_batch(_flip(images), model))
                        vectors /= np.maximum(np.linalg.norm(vectors, axis=1, keepdims=True), 1e-12)
                    for entry, vec in zip(ok, vectors):
                        rows.append((entry[0], entry[2], entry[3], encode_vector(vec)))
                        prog.done += 1
                if len(rows) >= 64:
                    flush()
                report()
            for _, fut in pending:
                fut.cancel()
    finally:
        flush()
    report(force=True)
    return Summary(
        total_images=len(files),
        newly_indexed=prog.done - prog.failed,
        already_cached=cached,
        failed=prog.failed,
        cancelled=cancel.is_set(),
        seconds=time.monotonic() - started,
    )
