import dataclasses
import re
import struct
import threading
from pathlib import Path

import numpy as np
import pytest
from PIL import Image

from lumen_indexer import catalog, exporter, imaging, indexer
from lumen_indexer.modelstore import ModelStore
from lumen_indexer.store import Store

REPO = Path(__file__).resolve().parents[2]


# ---------------------------------------------------------------- catalog == Android ModelCatalog.kt
def _kotlin_models():
    src = (REPO / "app/src/main/java/app/lumen/photos/ai/ModelCatalog.kt").read_text(encoding="utf-8")
    out = {}
    for block in re.split(r"\n\s*AiModel\(", src)[1:]:
        g = lambda pat: re.search(pat, block).group(1)  # noqa: E731
        out[g(r'id = "([^"]+)"')] = dict(
            repo=g(r'repo = "([^"]+)"'),
            dim=int(g(r"embeddingDim = (\d+)")),
            size=int(g(r"imageSize = (\d+)")),
            mode=g(r"resizeMode = ResizeMode\.(\w+)"),
            mean=g(r"mean = (\w+)"),
        )
    return out


@pytest.mark.skipif(not (REPO / "app").is_dir(), reason="needs the repository checkout")
def test_catalog_matches_the_android_app():
    kotlin = _kotlin_models()
    assert [m.id for m in catalog.MODELS] == list(kotlin), "model ids / order differ from ModelCatalog.kt"
    for m in catalog.MODELS:
        k = kotlin[m.id]
        assert m.repo == k["repo"]
        assert m.dim == k["dim"]
        assert m.image_size == k["size"]
        assert m.resize_mode == (catalog.SQUASH if k["mode"] == "SQUASH" else catalog.CROP)
        assert m.mean == ((0.0, 0.0, 0.0) if k["mean"] == "CLIP_ZERO" else (0.5, 0.5, 0.5))


# ---------------------------------------------------------------- vectors
def test_fp16_encoding_is_ieee_half_little_endian():
    values = [0.6, -0.8, 1.0, 0.0, 0.0001234, 0.333333]
    got = exporter.encode_vector(np.array(values, dtype=np.float32))
    expected = b"".join(struct.pack("<e", np.float32(v)) for v in values)
    assert got == expected


def _vec(dim, seed):
    v = np.random.default_rng(seed).normal(size=dim).astype(np.float32)
    return v / np.linalg.norm(v)


def test_export_roundtrip(tmp_path):
    model = dataclasses.replace(catalog.MODELS[0], dim=16)
    store = Store(tmp_path / "c.db")
    vecs = {f"A/p{i}.jpg": _vec(16, i) for i in range(5)}
    store.put_many("/r", model.id, [(rel, 10 + i, 100 + i, exporter.encode_vector(v)) for i, (rel, v) in enumerate(vecs.items())])
    store.put_many("/r", model.id, [("A/broken.jpg", 1, 1, None)])  # failed files are not exported
    store.put_many("/other", model.id, [("x.jpg", 1, 1, exporter.encode_vector(_vec(16, 99)))])

    target = tmp_path / "out.lumenindex"
    assert exporter.export_index(store, "/r", model, target) == 5
    idx = exporter.read_index(target)
    assert idx.manifest["modelId"] == model.id and idx.manifest["count"] == 5 and idx.manifest["dim"] == 16
    assert [(i["p"], i["n"]) for i in idx.items] == [("A", f"p{i}.jpg") for i in range(5)]
    for item, row in zip(idx.items, idx.vectors):
        expected = vecs[f"{item['p']}/{item['n']}"]
        assert np.allclose(row, expected, atol=1e-3)


def test_export_of_empty_folder_fails(tmp_path):
    with pytest.raises(ValueError):
        exporter.export_index(Store(tmp_path / "c.db"), "/nothing", catalog.MODELS[0], tmp_path / "x.lumenindex")
    assert not list(tmp_path.glob("x.lumenindex*"))


def test_zip_entries_are_in_the_order_the_phone_streams_them(tmp_path):
    import zipfile

    model = dataclasses.replace(catalog.MODELS[0], dim=4)
    store = Store(tmp_path / "c.db")
    store.put_many("/r", model.id, [("a.jpg", 1, 1, exporter.encode_vector(_vec(4, 1)))])
    exporter.export_index(store, "/r", model, tmp_path / "o.lumenindex")
    with zipfile.ZipFile(tmp_path / "o.lumenindex") as z:
        assert z.namelist() == ["manifest.json", "items.jsonl", "vectors.bin"]
        assert all(i.flag_bits & 0x08 == 0 for i in z.infolist()), "no data descriptors (Android streams the file)"


# ---------------------------------------------------------------- images
def test_scan_skips_hidden_and_non_images(tmp_path):
    (tmp_path / "Camera").mkdir()
    (tmp_path / ".thumbnails").mkdir()
    for p in ["Camera/a.jpg", "Camera/B.PNG", "Camera/.trashed-1-c.jpg", "Camera/notes.txt", ".thumbnails/t.jpg", "top.webp"]:
        (tmp_path / p).write_bytes(b"x")
    assert sorted(r[0] for r in imaging.scan_images(tmp_path)) == ["Camera/B.PNG", "Camera/a.jpg", "top.webp"]


def test_preprocessing_shapes_and_exif_orientation(tmp_path):
    m_squash = catalog.by_id("siglip2-b16-256")
    m_crop = catalog.by_id("mobileclip-s0")
    # 200x100: left half red, right half blue; EXIF orientation 6 = shown rotated 90° clockwise,
    # so the red half ends up on top.
    im = Image.new("RGB", (200, 100), (255, 0, 0))
    im.paste((0, 0, 255), (100, 0, 200, 100))
    exif = Image.Exif()
    exif[0x0112] = 6
    path = tmp_path / "r.jpg"
    im.save(path, quality=100, exif=exif)

    a = imaging.load_prepared(str(path), m_squash)
    assert a.shape == (256, 256, 3) and a.dtype == np.uint8
    assert a[10, 128, 0] > 200 and a[10, 128, 2] < 60  # top: red
    assert a[245, 128, 2] > 200 and a[245, 128, 0] < 60  # bottom: blue
    b = imaging.load_prepared(str(path), m_crop)
    assert b.shape == (256, 256, 3)
    # Centre crop of the rotated 100x200 image: a square from the middle, red above / blue below.
    assert b[20, 128, 0] > 200 and b[235, 128, 2] > 200


def test_normalize_matches_the_android_formula():
    m = catalog.by_id("siglip2-b16-256")
    px = np.full((2, 2, 3), 255, dtype=np.uint8)
    px[0, 0] = (0, 51, 255)
    x = imaging.normalize_batch([px], dataclasses.replace(m, image_size=2))
    assert x.shape == (1, 3, 2, 2) and x.dtype == np.float32
    assert np.allclose(x[0, :, 0, 0], [-1.0, -0.6, 1.0])  # (v/255 - 0.5) / 0.5, CHW order
    clip = catalog.by_id("mobileclip-s0")
    x = imaging.normalize_batch([px], dataclasses.replace(clip, image_size=2))
    assert np.allclose(x[0, :, 0, 0], [0.0, 0.2, 1.0])  # v/255


# ---------------------------------------------------------------- indexer (with a fake engine)
class FakeEngine:
    created = 0

    def __init__(self, model, store, threads=None):
        FakeEngine.created += 1
        self.model, self.batch_size, self.accelerated, self.device_label = model, 1, False, "Test"

    def embed(self, batch):
        out = batch.reshape(batch.shape[0], -1)[:, : self.model.dim].astype(np.float32)
        out = out + 1.0
        return out / np.linalg.norm(out, axis=1, keepdims=True)


@pytest.fixture
def small_model():
    return dataclasses.replace(catalog.by_id("mobileclip-s0"), image_size=8, dim=8)


def _photos(root: Path, n: int):
    (root / "Camera").mkdir(parents=True, exist_ok=True)
    for i in range(n):
        Image.new("RGB", (40, 30), (i * 20 % 255, 100, 50)).save(root / "Camera" / f"PXL_2024031{i}_1.jpg")


def _run(root, model, store, cancel=None):
    return indexer.run_index(root, model, store, ModelStore(root / "_m"), lambda p: None, cancel or threading.Event(), 2)


def test_index_run_is_resumable_and_incremental(tmp_path, monkeypatch, small_model):
    monkeypatch.setattr(indexer, "Engine", FakeEngine)
    root = tmp_path / "photos"
    _photos(root, 5)
    (root / "Camera" / "broken.jpg").write_bytes(b"not an image")
    store = Store(tmp_path / "c.db")
    key = str(root.resolve())

    s1 = _run(root, small_model, store)
    assert (s1.total_images, s1.newly_indexed, s1.failed, s1.already_cached) == (6, 5, 1, 0)
    assert store.count(key, small_model.id) == 5

    FakeEngine.created = 0
    s2 = _run(root, small_model, store)  # nothing changed: no model load at all
    assert (s2.newly_indexed, s2.failed, s2.already_cached) == (0, 0, 6) and FakeEngine.created == 0

    Image.new("RGB", (40, 30), (1, 2, 3)).save(root / "Camera" / "PXL_new_1.jpg")  # a new photo
    (root / "Camera" / "PXL_20240310_1.jpg").unlink()  # a removed one
    s3 = _run(root, small_model, store)
    assert (s3.newly_indexed, s3.already_cached) == (1, 5)
    assert store.count(key, small_model.id) == 5  # +1 new, -1 removed


def test_cancel_keeps_progress(tmp_path, monkeypatch, small_model):
    root = tmp_path / "photos"
    _photos(root, 8)
    store = Store(tmp_path / "c.db")
    cancel = threading.Event()

    class StopAfterTwo(FakeEngine):
        calls = 0

        def embed(self, batch):
            StopAfterTwo.calls += 1
            if StopAfterTwo.calls == 2:
                cancel.set()
            return super().embed(batch)

    monkeypatch.setattr(indexer, "Engine", StopAfterTwo)
    s = _run(root, small_model, store, cancel)
    assert s.cancelled and 2 <= s.newly_indexed < 8
    done = store.count(str(root.resolve()), small_model.id)
    assert done == s.newly_indexed

    monkeypatch.setattr(indexer, "Engine", FakeEngine)
    s2 = _run(root, small_model, store)
    assert not s2.cancelled and s2.newly_indexed == 8 - done
