"""The ".lumenindex" file: what the desktop app exports and the Android app imports.

A ZIP container (entries in exactly this order, so the phone can stream it):

  manifest.json  {"format": "lumen-index", "version": 1, "modelId", "modelName", "dim", "count",
                  "encoding": "fp16-le-l2", "createdAt", "createdBy", "source"}
  items.jsonl    one JSON object per line: {"n": file name, "p": folder relative to the chosen
                 folder ("" = top level), "s": size in bytes, "m": modification time (epoch s)}
  vectors.bin    count * dim half-floats, little endian, L2-normalised – the same bytes the app
                 stores in its Room table (Fp16.encode). Vector i belongs to line i of items.jsonl.

The phone matches items to its photos by (file name, size) – photos copied from the phone keep both.
"""
from __future__ import annotations

import json
import zipfile
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterator

import numpy as np

from . import __version__
from .catalog import AiModel
from .store import Store

FORMAT = "lumen-index"
FORMAT_VERSION = 1
ENCODING = "fp16-le-l2"
EXTENSION = ".lumenindex"


def encode_vector(v: np.ndarray) -> bytes:
    """L2-normalised float32 vector -> fp16 little endian (identical rounding to Fp16.kt)."""
    return np.asarray(v, dtype="<f2").tobytes()


def decode_vector(b: bytes) -> np.ndarray:
    return np.frombuffer(b, dtype="<f2").astype(np.float32)


def default_filename(model: AiModel, source: str) -> str:
    stamp = datetime.now().strftime("%Y-%m-%d")
    safe = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in source)[:40] or "Fotos"
    return f"Lumen-Index_{safe}_{model.id}_{stamp}{EXTENSION}"


def export_index(store: Store, root: str, model: AiModel, target: Path,
                 on_progress: Callable[[int, int], None] | None = None, key: str | None = None) -> int:
    """Writes [target]; returns the number of exported photos. [key]: cache key if not model.id
    (vectors made with "Hoch")."""
    target = Path(target)
    key = key or model.id
    tmp = target.with_name(target.name + ".part")
    with store.snapshot() as snap:
        count = snap.count(root, key)
        if count == 0:
            raise ValueError("Für diesen Ordner und dieses Modell gibt es noch keine Indexierung.")
        manifest = {
            "format": FORMAT,
            "version": FORMAT_VERSION,
            "modelId": model.id,
            "modelName": model.name,
            "dim": model.dim,
            "count": count,
            "encoding": ENCODING,
            "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "createdBy": f"Lumen Indexer {__version__}",
            "source": Path(root).name,
        }
        try:
            with zipfile.ZipFile(tmp, "w", allowZip64=True) as z:
                z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False), compress_type=zipfile.ZIP_DEFLATED)
                with z.open("items.jsonl", "w") as out:
                    for rel, size, mtime in snap.items(root, key):
                        folder, _, name = rel.rpartition("/")
                        line = json.dumps({"n": name, "p": folder, "s": size, "m": mtime}, ensure_ascii=False, separators=(",", ":"))
                        out.write(line.encode("utf-8") + b"\n")
                zi = zipfile.ZipInfo("vectors.bin", date_time=datetime.now().timetuple()[:6])
                zi.compress_type = zipfile.ZIP_STORED  # half floats do not compress
                done = 0
                # Plain ZIP unless the vectors need ZIP64 (> 2 GB): the phone reads both, plain is safest.
                with z.open(zi, "w", force_zip64=count * model.dim * 2 >= 0x7FFF0000) as out:
                    for vec in snap.vectors(root, key):
                        if len(vec) != model.dim * 2:
                            raise ValueError("Der Zwischenspeicher enthält Vektoren mit falscher Länge – bitte neu indexieren.")
                        out.write(vec)
                        done += 1
                        if on_progress and done % 500 == 0:
                            on_progress(done, count)
            tmp.replace(target)
        except BaseException:
            tmp.unlink(missing_ok=True)
            raise
    if on_progress:
        on_progress(count, count)
    return count


FACES_FORMAT = "lumen-faces"
FACES_VERSION = 1


def default_faces_filename(model_id: str, source: str) -> str:
    stamp = datetime.now().strftime("%Y-%m-%d")
    safe = "".join(ch if ch.isalnum() or ch in "-_" else "_" for ch in source)[:40] or "Fotos"
    return f"Lumen-Gesichter_{safe}_{model_id}_{stamp}{EXTENSION}"


def export_faces(store: Store, root: str, model, target: Path, on_progress: Callable[[int, int], None] | None = None) -> tuple[int, int]:
    """Writes the faces of every scanned photo (also those without faces, so the phone skips them).

    Same container as the search index (the phone tells them apart by "format"):
      manifest.json  {"format": "lumen-faces", "version": 1, "modelId", "dim", "count", "faces", ...}
      items.jsonl    {"n", "p", "s", "m", "f": [[left, top, right, bottom, score], ...]} (box 0..1)
      vectors.bin    one fp16 vector per face, in the order of the items and their "f" lists
    Returns (photos, faces).
    """
    target = Path(target)
    tmp = target.with_name(target.name + ".part")
    with store.snapshot() as snap:
        rows = list(snap.face_rows(root, model.id))
        if not rows:
            raise ValueError("Für diesen Ordner und dieses Modell gibt es noch keine Gesichtserkennung.")
        faces = sum(r[3] for r in rows)
        manifest = {
            "format": FACES_FORMAT,
            "version": FACES_VERSION,
            "modelId": model.id,
            "modelName": model.name,
            "dim": model.dim,
            "count": len(rows),
            "faces": faces,
            "encoding": ENCODING,
            "createdAt": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "createdBy": f"Lumen Indexer {__version__}",
            "source": Path(root).name,
        }
        try:
            with zipfile.ZipFile(tmp, "w", allowZip64=True) as z:
                z.writestr("manifest.json", json.dumps(manifest, ensure_ascii=False), compress_type=zipfile.ZIP_DEFLATED)
                with z.open("items.jsonl", "w") as out:
                    for rel, size, mtime, n, boxes, _ in rows:
                        folder, _, name = rel.rpartition("/")
                        b = np.frombuffer(boxes, dtype="<f4").reshape(n, 5) if n else np.zeros((0, 5), "<f4")
                        item = {"n": name, "p": folder, "s": size, "m": mtime, "f": [[round(float(v), 5) for v in row] for row in b]}
                        out.write(json.dumps(item, ensure_ascii=False, separators=(",", ":")).encode("utf-8") + b"\n")
                zi = zipfile.ZipInfo("vectors.bin", date_time=datetime.now().timetuple()[:6])
                zi.compress_type = zipfile.ZIP_STORED
                with z.open(zi, "w", force_zip64=faces * model.dim * 2 >= 0x7FFF0000) as out:
                    for i, (_, _, _, n, _, vecs) in enumerate(rows):
                        if len(vecs) != n * model.dim * 2:
                            raise ValueError("Der Zwischenspeicher enthält Gesichter mit falscher Länge – bitte neu scannen.")
                        out.write(vecs)
                        if on_progress and i % 500 == 0:
                            on_progress(i, len(rows))
            tmp.replace(target)
        except BaseException:
            tmp.unlink(missing_ok=True)
            raise
    if on_progress:
        on_progress(len(rows), len(rows))
    return len(rows), faces


@dataclass
class IndexFile:
    manifest: dict
    items: list[dict]
    vectors: np.ndarray  # (count, dim) float32


def read_index(path: Path) -> IndexFile:
    """Reads a whole export (used by tests and the `inspect` command). For face files the vectors
    are the faces in item order."""
    with zipfile.ZipFile(path) as z:
        manifest = json.loads(z.read("manifest.json"))
        if manifest.get("format") not in (FORMAT, FACES_FORMAT):
            raise ValueError("Keine Lumen-Indexierungsdatei")
        items = [json.loads(line) for line in z.read("items.jsonl").decode("utf-8").splitlines() if line]
        raw = np.frombuffer(z.read("vectors.bin"), dtype="<f2")
    dim = manifest["dim"]
    rows = sum(len(i.get("f", [])) for i in items) if manifest["format"] == FACES_FORMAT else len(items)
    if len(items) != manifest["count"] or raw.size != rows * dim:
        raise ValueError("Beschädigte Indexierungsdatei")
    return IndexFile(manifest, items, raw.astype(np.float32).reshape(rows, dim))
