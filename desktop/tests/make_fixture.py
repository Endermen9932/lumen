"""Writes app/src/test/resources/sample.lumenindex and sample_faces.lumenindex with the real exporter.

The Android unit test IndexImportTest parses these files, so a format change on either side fails a
test. Re-run only when the format changes:  python tests/make_fixture.py
"""
import dataclasses
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lumen_indexer import catalog, faces  # noqa: E402
from lumen_indexer.exporter import encode_vector, export_faces, export_index  # noqa: E402
from lumen_indexer.store import Store  # noqa: E402

ROWS = [
    ("Camera/IMG_0001.jpg", 2222, 1710000001, [1, 0, 0, 0]),
    ("Camera/PXL_20240312_120000.jpg", 1111, 1710000000, [0.5, -0.5, 0.5, -0.5]),
    ("Screenshots/Screenshot_20240313-101500.png", 3333, 1710000002, [0, 0, 0.6, 0.8]),
    ("Ümläut ünd Spaß/Foto ä.jpg", 4444, 1710000003, [0, 1, 0, 0]),
]

if __name__ == "__main__":
    model = dataclasses.replace(catalog.MODELS[0], dim=4)
    out = Path(__file__).resolve().parents[2] / "app/src/test/resources/sample.lumenindex"
    with tempfile.TemporaryDirectory() as tmp:
        store = Store(Path(tmp) / "t.db")
        store.put_many("/photos", model.id, [(rel, s, m, encode_vector(np.array(v, dtype=np.float32))) for rel, s, m, v in ROWS])
        out.parent.mkdir(parents=True, exist_ok=True)
        export_index(store, "/photos", model, out)
    print("written", out, out.stat().st_size, "bytes")

    face_model = dataclasses.replace(faces.face_model("face-buffalo-l"), dim=4)
    out = out.with_name("sample_faces.lumenindex")
    with tempfile.TemporaryDirectory() as tmp:
        store = Store(Path(tmp) / "t.db")
        rows = []
        for rel, s, m, fl in [
            ("Camera/PXL_20240312_120000.jpg", 1111, 1710000000, [faces.Face((0.1, 0.2, 0.3, 0.4), 0.9, np.array([1, 0, 0, 0], np.float32)),
                                                                   faces.Face((0.5, 0.5, 0.75, 0.8), 0.75, np.array([0, 0.6, 0.8, 0], np.float32))]),
            ("Camera/IMG_0001.jpg", 2222, 1710000001, []),
        ]:
            boxes, vecs = faces.encode_faces(fl)
            rows.append((rel, s, m, "std", len(fl), boxes, vecs))
        store.face_put_many("/photos", face_model.id, rows)
        export_faces(store, "/photos", face_model, out)
    print("written", out, out.stat().st_size, "bytes")
