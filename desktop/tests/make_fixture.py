"""Writes app/src/test/resources/sample.lumenindex with the real exporter.

The Android unit test IndexImportTest parses this file, so a format change on either side fails a
test. Re-run only when the format changes:  python tests/make_fixture.py
"""
import dataclasses
import sys
import tempfile
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from lumen_indexer import catalog  # noqa: E402
from lumen_indexer.exporter import encode_vector, export_index  # noqa: E402
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
