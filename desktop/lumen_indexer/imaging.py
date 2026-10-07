"""Image decoding and preprocessing – the PC counterpart of EmbeddingEngine.prepare() on Android."""
from __future__ import annotations

import os
from pathlib import Path

import numpy as np
from PIL import Image, ImageOps

from .catalog import CROP, AiModel

Image.MAX_IMAGE_PIXELS = None  # panoramas and 200 MP photos are legitimate here

IMAGE_EXTENSIONS = frozenset({
    ".jpg", ".jpeg", ".jpe", ".png", ".webp", ".heic", ".heif", ".avif", ".bmp", ".gif", ".tif", ".tiff",
})

_heif_registered = False


def _register_heif() -> None:
    global _heif_registered
    if _heif_registered:
        return
    _heif_registered = True
    try:
        import pillow_heif

        pillow_heif.register_heif_opener()
        try:
            pillow_heif.register_avif_opener()
        except Exception:  # older versions
            pass
    except Exception:
        pass  # HEIC photos are then reported as unreadable instead of crashing the app


def scan_images(root: Path):
    """Yields (relative posix path, absolute path, size, mtime seconds) of every image below root.

    Hidden files/folders are skipped (Android keeps deleted photos as ".trashed-…").
    """
    root = Path(root)
    for dirpath, dirnames, filenames in os.walk(root):
        dirnames[:] = sorted(d for d in dirnames if not d.startswith("."))
        for name in sorted(filenames):
            if name.startswith(".") or os.path.splitext(name)[1].lower() not in IMAGE_EXTENSIONS:
                continue
            full = os.path.join(dirpath, name)
            try:
                st = os.stat(full)
            except OSError:
                continue
            rel = os.path.relpath(full, root).replace(os.sep, "/")
            yield rel, full, st.st_size, int(st.st_mtime)


def load_prepared(path: str, model: AiModel) -> np.ndarray:
    """Decodes [path] and returns a uint8 array (size, size, 3) ready for [normalize_batch]."""
    _register_heif()
    size = model.image_size
    with Image.open(path) as im:
        if im.format == "JPEG":
            # Let libjpeg decode at 1/2, 1/4 or 1/8 scale: several times faster for 12-50 MP photos.
            im.draft("RGB", (size, size))
        im = ImageOps.exif_transpose(im)
        if im.mode in ("RGBA", "LA", "PA") or (im.mode == "P" and "transparency" in im.info):
            im = im.convert("RGBA")
        im = im.convert("RGB")
        if model.resize_mode == CROP:
            w, h = im.size
            scale = size / min(w, h)
            nw, nh = max(size, round(w * scale)), max(size, round(h * scale))
            im = im.resize((nw, nh), Image.BICUBIC)
            left, top = (nw - size) // 2, (nh - size) // 2
            im = im.crop((left, top, left + size, top + size))
        else:
            im = im.resize((size, size), Image.BICUBIC)
        return np.asarray(im, dtype=np.uint8)


def normalize_batch(images: list[np.ndarray], model: AiModel) -> np.ndarray:
    """Stacks uint8 HWC images into a float32 NCHW tensor (x/255 - mean) / std."""
    x = np.stack(images).astype(np.float32)
    mean = np.asarray(model.mean, dtype=np.float32)
    std = np.asarray(model.std, dtype=np.float32)
    x *= 1.0 / (255.0 * std)
    x -= mean / std
    return np.ascontiguousarray(x.transpose(0, 3, 1, 2))
