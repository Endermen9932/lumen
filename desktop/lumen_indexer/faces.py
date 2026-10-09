"""Face detection (SCRFD) + recognition (ArcFace) – the PC twin of FaceEngine.kt / FaceWorker.kt.

The same models (same ids, files and preprocessing) as the Android app, so the face embeddings the
PC exports land in the same vector space as the ones the phone computes for new photos – the phone
keeps grouping both together.

"Hoch" (high quality) improves only what does not change that space: the larger SCRFD-10G detector
(also for "Schnell", whose phone detector is the tiny SCRFD-500M), a 1024 px instead of 640 px
detection input (finds small faces in group photos) and alignment crops from a sharper image. The
recognition network – the part that defines the vectors – is always the phone's.
"""
from __future__ import annotations

import threading
import time
from collections import deque
from concurrent.futures import Future, ThreadPoolExecutor
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

import numpy as np
from PIL import Image, ImageOps

from .catalog import ModelFile
from .engine import cpu_threads, create_session, device_label
from .imaging import _register_heif, scan_images
from .modelstore import ModelStore

# ------------------------------------------------------------------ catalog (== FaceModelCatalog.kt)


@dataclass(frozen=True)
class FaceModel:
    id: str
    tier: str
    name: str
    description: str
    repo: str
    files: tuple[ModelFile, ...]  # detection, recognition
    dim: int = 512

    @property
    def total_bytes(self) -> int:
        return sum(f.size for f in self.files)

    def url(self, file: ModelFile) -> str:
        return f"https://huggingface.co/{self.repo}/resolve/main/{file.path}"


_DET_10G = ModelFile("detection/model.onnx", 16_923_827)

FACE_MODELS: tuple[FaceModel, ...] = (
    FaceModel("face-buffalo-s", "Schnell", "SCRFD 500M + MobileFaceNet",
              "Winzig und flott. Gut für große Galerien.", "immich-app/buffalo_s",
              (ModelFile("detection/model.onnx", 2_524_817), ModelFile("recognition/model.onnx", 13_616_099))),
    FaceModel("face-buffalo-l", "Ausgewogen", "SCRFD 10G + ArcFace R50",
              "Starker Detektor und genaue Wiedererkennung – der empfohlene Standard.", "immich-app/buffalo_l",
              (_DET_10G, ModelFile("recognition/model.onnx", 174_383_860))),
    FaceModel("face-antelopev2", "Sehr gut", "SCRFD 10G + ArcFace R100 (Glint360K)",
              "Das genaueste Modell: erkennt Personen auch über Jahre.", "immich-app/antelopev2",
              (_DET_10G, ModelFile("recognition/model.onnx", 260_665_334))),
)

DEFAULT_FACE_MODEL_ID = "face-buffalo-l"

# The better detector for "Hoch" (only "Schnell" has a different one on the phone).
HQ_DETECTOR_REPO = "immich-app/buffalo_l"


def face_model(model_id: str) -> FaceModel | None:
    return next((m for m in FACE_MODELS if m.id == model_id), None)


class FaceModelStore:
    """Model files in models/<id>/detection.onnx, recognition.onnx (+ detection_hq.onnx)."""

    def __init__(self, store: ModelStore | None = None):
        self.base = store or ModelStore()

    def dir(self, m: FaceModel) -> Path:
        return self.base.root / m.id

    def files(self, m: FaceModel, hq: bool) -> list[tuple[str, ModelFile, Path]]:
        d = self.dir(m)
        out = [(m.url(m.files[0]), m.files[0], d / "detection.onnx"), (m.url(m.files[1]), m.files[1], d / "recognition.onnx")]
        if hq and m.files[0].size != _DET_10G.size:
            out.append((f"https://huggingface.co/{HQ_DETECTOR_REPO}/resolve/main/{_DET_10G.path}", _DET_10G, d / "detection_hq.onnx"))
        return out

    def detector(self, m: FaceModel, hq: bool) -> Path:
        d = self.dir(m)
        return d / ("detection_hq.onnx" if hq and m.files[0].size != _DET_10G.size else "detection.onnx")

    def recognizer(self, m: FaceModel) -> Path:
        return self.dir(m) / "recognition.onnx"

    def is_installed(self, m: FaceModel, hq: bool = False) -> bool:
        return all(p.is_file() and p.stat().st_size == f.size for _, f, p in self.files(m, hq))

    def download_bytes(self, m: FaceModel, hq: bool) -> int:
        return sum(f.size for _, f, p in self.files(m, hq) if not (p.is_file() and p.stat().st_size == f.size))

    def download(self, m: FaceModel, hq: bool, on_progress, cancel: threading.Event | None = None) -> None:
        from .modelstore import Cancelled, _download_file

        todo = [(u, f, p) for u, f, p in self.files(m, hq) if not (p.is_file() and p.stat().st_size == f.size)]
        total = sum(f.size for _, f, _ in todo)
        base = 0
        self.dir(m).mkdir(parents=True, exist_ok=True)
        for url, f, target in todo:
            part = target.with_name(target.name + ".part")
            for attempt in range(5):
                try:
                    _download_file(url, part, lambda done, b=base: on_progress(b + done, total), cancel)
                    break
                except Cancelled:
                    raise
                except OSError:
                    if attempt == 4:
                        raise
                    time.sleep(2.0 * (attempt + 1))
            if part.stat().st_size != f.size:
                part.unlink(missing_ok=True)
                raise OSError(f"Unvollständige Datei {f.path}")
            part.replace(target)
            base += f.size


# ------------------------------------------------------------------ engine

_TEMPLATE = np.array([[38.2946, 51.6963], [73.5318, 51.5014], [56.0252, 71.7366], [41.5493, 92.3655], [70.7299, 92.2041]], dtype=np.float32)
_STRIDES = (8, 16, 32)

# Like the phone: faces smaller than this (in px of a 1280 px image) are ignored, at most 30 per photo.
_MIN_FACE_PX_AT_1280 = 20.0
MAX_FACES = 30
SCORE_THRESHOLD = 0.55


@dataclass
class Face:
    box: tuple[float, float, float, float]  # normalised 0..1 of the oriented image
    score: float
    vector: np.ndarray  # L2-normalised float32


class FaceEngine:
    def __init__(self, model: FaceModel, store: FaceModelStore, hq: bool, threads: int | None = None, use_gpu: bool = True):
        self.model = model
        self.hq = hq
        self.det_size = 1024 if hq else 640
        self.det = create_session(str(store.detector(model, hq)), threads, use_gpu)
        self.rec = create_session(str(store.recognizer(model)), threads, use_gpu)
        self._det_in = self.det.get_inputs()[0].name
        self._rec_in = self.rec.get_inputs()[0].name
        self.provider = self.det.get_providers()[0]

    @property
    def device_label(self) -> str:
        return device_label(self.provider)

    def detect(self, img: Image.Image) -> list[tuple[np.ndarray, np.ndarray, float]]:
        """(box xyxy px, landmarks (5,2) px, score) of the faces in an RGB image, best first."""
        size = self.det_size
        w, h = img.size
        scale = size / max(w, h)
        small = img.resize((max(1, round(w * scale)), max(1, round(h * scale))), Image.BILINEAR)
        canvas = np.zeros((size, size, 3), dtype=np.float32)
        arr = np.asarray(small, dtype=np.float32)
        canvas[: arr.shape[0], : arr.shape[1]] = arr
        x = ((canvas - 127.5) / 128.0).transpose(2, 0, 1)[None]
        outs = self.det.run(None, {self._det_in: np.ascontiguousarray(x)})
        min_px = _MIN_FACE_PX_AT_1280 * max(w, h) / 1280.0
        cands = []
        for level, stride in enumerate(_STRIDES):
            scores = outs[level].reshape(-1)
            boxes = outs[level + 3].reshape(-1, 4)
            kps = outs[level + 6].reshape(-1, 10)
            grid = size // stride
            for j in np.nonzero(scores >= SCORE_THRESHOLD)[0]:
                cell = j // 2
                cx = (cell % grid) * stride
                cy = (cell // grid) * stride
                b = boxes[j] * stride
                box = np.array([cx - b[0], cy - b[1], cx + b[2], cy + b[3]], dtype=np.float32) / scale
                if box[2] - box[0] < min_px or box[3] - box[1] < min_px:
                    continue
                k = kps[j] * stride
                lm = np.stack([(cx + k[0::2]) / scale, (cy + k[1::2]) / scale], axis=1).astype(np.float32)
                cands.append((box, lm, float(scores[j])))
        cands.sort(key=lambda c: -c[2])
        keep: list = []
        for c in cands:
            if all(_iou(c[0], k[0]) <= 0.4 for k in keep):
                keep.append(c)
        return keep[:MAX_FACES]

    def align(self, img: Image.Image, lm: np.ndarray) -> np.ndarray:
        """112×112 crop, same least-squares similarity transform as FaceEngine.kt."""
        src_mean = lm.mean(axis=0)
        dst_mean = _TEMPLATE.mean(axis=0)
        p = lm - src_mean
        q = _TEMPLATE - dst_mean
        den = float((p ** 2).sum())
        a = float((p[:, 0] * q[:, 0] + p[:, 1] * q[:, 1]).sum()) / den
        b = float((p[:, 0] * q[:, 1] - p[:, 1] * q[:, 0]).sum()) / den
        tx = dst_mean[0] - (a * src_mean[0] - b * src_mean[1])
        ty = dst_mean[1] - (b * src_mean[0] + a * src_mean[1])
        forward = np.array([[a, -b, tx], [b, a, ty], [0, 0, 1]], dtype=np.float64)
        inv = np.linalg.inv(forward)
        crop = img.transform((112, 112), Image.AFFINE, data=tuple(inv[:2].reshape(-1)), resample=Image.BILINEAR, fillcolor=(0, 0, 0))
        return np.asarray(crop, dtype=np.float32)

    def embed(self, crops: list[np.ndarray]) -> np.ndarray:
        x = ((np.stack(crops) - 127.5) / 127.5).transpose(0, 3, 1, 2)
        out = np.concatenate([self.rec.run(None, {self._rec_in: np.ascontiguousarray(x[i:i + 1])})[0] for i in range(len(crops))]) \
            if self.provider == "CPUExecutionProvider" else self.rec.run(None, {self._rec_in: np.ascontiguousarray(x)})[0]
        out = np.asarray(out, dtype=np.float32).reshape(len(crops), -1)
        norms = np.linalg.norm(out, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        return out / norms

    def analyse(self, img: Image.Image) -> list[Face]:
        w, h = img.size
        found = self.detect(img)
        if not found:
            return []
        vectors = self.embed([self.align(img, lm) for _, lm, _ in found])
        return [
            Face((float(np.clip(b[0] / w, 0, 1)), float(np.clip(b[1] / h, 0, 1)), float(np.clip(b[2] / w, 0, 1)), float(np.clip(b[3] / h, 0, 1))), s, v)
            for (b, _, s), v in zip(found, vectors)
        ]


def _iou(a: np.ndarray, b: np.ndarray) -> float:
    ix = max(0.0, min(a[2], b[2]) - max(a[0], b[0]))
    iy = max(0.0, min(a[3], b[3]) - max(a[1], b[1]))
    inter = ix * iy
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
    return float(inter / union) if union > 0 else 0.0


def load_for_faces(path: str, hq: bool) -> Image.Image:
    """The oriented RGB photo, at most 1280 px (phone) or 2048 px (high quality) on the long edge."""
    _register_heif()
    limit = 2048 if hq else 1280
    with Image.open(path) as im:
        if im.format == "JPEG":
            im.draft("RGB", (limit, limit))
        im = ImageOps.exif_transpose(im).convert("RGB")
        if max(im.size) > limit:
            s = limit / max(im.size)
            im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), Image.BILINEAR)
        im.load()
        return im


# ------------------------------------------------------------------ run


@dataclass
class FaceProgress:
    done: int = 0
    total: int = 0
    failed: int = 0
    faces: int = 0
    per_second: float = 0.0
    eta_seconds: float = 0.0
    phase: str = "scan"
    found: int = 0
    device: str = ""


@dataclass
class FaceSummary:
    total_images: int
    newly_scanned: int
    already_cached: int
    faces: int
    failed: int
    cancelled: bool
    seconds: float


def encode_faces(faces: list[Face]) -> tuple[bytes, bytes]:
    boxes = np.array([[*f.box, f.score] for f in faces], dtype="<f4").reshape(-1, 5)
    vecs = np.array([f.vector for f in faces], dtype="<f2").reshape(len(faces), -1) if faces else np.zeros((0, 0), "<f2")
    return boxes.tobytes(), vecs.tobytes()


def run_faces(
    root: Path,
    model: FaceModel,
    hq: bool,
    store,
    models: FaceModelStore,
    on_progress: Callable[[FaceProgress], None],
    cancel: threading.Event,
    threads: int | None = None,
    use_gpu: bool = True,
) -> FaceSummary:
    started = time.monotonic()
    root = Path(root).resolve()
    key = str(root)
    variant = "hq" if hq else "std"
    prog = FaceProgress()
    files = []
    for entry in scan_images(root):
        files.append(entry)
        if len(files) % 200 == 0:
            prog.found = len(files)
            on_progress(prog)
        if cancel.is_set():
            return FaceSummary(len(files), 0, 0, 0, 0, True, time.monotonic() - started)
    prog.found = len(files)
    known = store.face_known(key, model.id)
    store.face_prune(key, model.id, {f[0] for f in files})
    todo = [f for f in files if known.get(f[0]) != (f[2], f[3], variant)]
    cached = len(files) - len(todo)
    prog.total = len(todo)
    if not todo:
        prog.phase = "run"
        on_progress(prog)
        return FaceSummary(len(files), 0, cached, 0, 0, False, time.monotonic() - started)

    prog.phase = "load"
    on_progress(prog)
    threads = threads or cpu_threads()
    engine = FaceEngine(model, models, hq, threads, use_gpu)
    prog.device = engine.device_label
    prog.phase = "run"
    on_progress(prog)

    decoders = max(2, min(8, threads // 2))
    pending: deque[tuple[tuple, Future]] = deque()
    rows: list = []
    t0 = time.monotonic()
    last_ui = 0.0
    it = iter(todo)

    def flush():
        if rows:
            store.face_put_many(key, model.id, rows)
            rows.clear()

    try:
        with ThreadPoolExecutor(max_workers=decoders, thread_name_prefix="faces") as pool:
            exhausted = False
            while True:
                while not exhausted and len(pending) < decoders * 2:
                    entry = next(it, None)
                    if entry is None:
                        exhausted = True
                        break
                    pending.append((entry, pool.submit(load_for_faces, entry[1], hq)))
                if not pending or cancel.is_set():
                    break
                entry, fut = pending.popleft()
                try:
                    faces = engine.analyse(fut.result())
                    boxes, vecs = encode_faces(faces)
                    rows.append((entry[0], entry[2], entry[3], variant, len(faces), boxes, vecs))
                    prog.faces += len(faces)
                except Exception:  # noqa: BLE001 – unreadable file: remembered, not retried
                    rows.append((entry[0], entry[2], entry[3], variant, -1, b"", b""))
                    prog.failed += 1
                prog.done += 1
                if len(rows) >= 32:
                    flush()
                now = time.monotonic()
                if now - last_ui > 0.3:
                    last_ui = now
                    prog.per_second = prog.done / max(now - t0, 1e-6)
                    prog.eta_seconds = (prog.total - prog.done) / prog.per_second if prog.per_second else 0.0
                    on_progress(prog)
            for _, f in pending:
                f.cancel()
    finally:
        flush()
    on_progress(prog)
    return FaceSummary(len(files), prog.done - prog.failed, cached, prog.faces, prog.failed, cancel.is_set(), time.monotonic() - started)
