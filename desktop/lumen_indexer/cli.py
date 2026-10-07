"""Command line interface (also used for headless runs and by the tests)."""
from __future__ import annotations

import argparse
import sys
import threading
from pathlib import Path

from . import APP_NAME, __version__, catalog
from .exporter import default_filename, export_index, read_index
from .indexer import Progress, run_index
from .modelstore import Cancelled, ModelStore
from .store import Store


def _fmt_eta(seconds: float) -> str:
    seconds = int(seconds)
    if seconds < 60:
        return f"{seconds} s"
    if seconds < 3600:
        return f"{seconds // 60} min"
    return f"{seconds // 3600} h {(seconds % 3600) // 60} min"


def _model(arg: str) -> catalog.AiModel:
    m = catalog.by_id(arg)
    if m is None:
        raise SystemExit(f"Unbekanntes Modell '{arg}'. Verfügbar: " + ", ".join(x.id for x in catalog.MODELS))
    return m


def _download(models: ModelStore, model: catalog.AiModel) -> None:
    def progress(done: int, total: int) -> None:
        print(f"\rLade {model.name}: {done / 1e6:,.0f} / {total / 1e6:,.0f} MB ({done * 100 // max(total, 1)} %)", end="", flush=True)

    models.download(model, progress)
    print()


def cmd_models(args) -> int:
    models = ModelStore()
    for m in catalog.MODELS:
        state = "installiert" if models.is_installed(m) else f"{m.total_bytes / 1e9:.2f} GB Download"
        print(f"{m.id:22} {m.tier:11} {m.name:26} dim {m.dim:<5} {state}")
    return 0


def cmd_download(args) -> int:
    _download(ModelStore(), _model(args.model))
    return 0


def cmd_index(args) -> int:
    model = _model(args.model)
    root = Path(args.folder).expanduser()
    if not root.is_dir():
        raise SystemExit(f"Ordner nicht gefunden: {root}")
    models, store = ModelStore(), Store()
    if not models.is_installed(model):
        print(f"Modell {model.name} fehlt – wird heruntergeladen.")
        _download(models, model)

    def show(p: Progress) -> None:
        if p.phase == "scan":
            print(f"\rSuche Bilder … {p.found}", end="", flush=True)
        elif p.phase == "load":
            print(f"\n{p.total} neue Bilder – lade Modell …", end="", flush=True)
        else:
            print(f"\r{p.done}/{p.total} · {p.per_second:.1f} Bilder/s · noch ca. {_fmt_eta(p.eta_seconds)} · {p.device}   ", end="", flush=True)

    cancel = threading.Event()
    try:
        summary = run_index(root, model, store, models, show, cancel, args.threads)
    except KeyboardInterrupt:
        cancel.set()
        print("\nAbgebrochen – der Fortschritt bleibt gespeichert.")
        return 130
    print(f"\nFertig: {summary.newly_indexed} neu indexiert, {summary.already_cached} aus dem Zwischenspeicher, "
          f"{summary.failed} nicht lesbar, {summary.seconds:.0f} s.")
    if args.export:
        out = Path(args.export)
        if out.is_dir():
            out = out / default_filename(model, root.name)
        n = export_index(store, str(root.resolve()), model, out)
        print(f"{n} Bilder exportiert nach {out}")
    return 0


def cmd_export(args) -> int:
    model = _model(args.model)
    root = Path(args.folder).expanduser().resolve()
    out = Path(args.output)
    if out.is_dir():
        out = out / default_filename(model, root.name)
    n = export_index(Store(), str(root), model, out)
    print(f"{n} Bilder exportiert nach {out}")
    return 0


def cmd_inspect(args) -> int:
    idx = read_index(Path(args.file))
    print({k: v for k, v in idx.manifest.items()})
    print(f"{len(idx.items)} Einträge, Vektoren {idx.vectors.shape}")
    for item in idx.items[:5]:
        print("  ", item)
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="lumen-indexer", description=f"{APP_NAME} {__version__}")
    parser.add_argument("--version", action="version", version=f"{APP_NAME} {__version__}")
    sub = parser.add_subparsers(dest="cmd")
    sub.add_parser("gui", help="Fenster öffnen (Standard)")
    sub.add_parser("models", help="Modelle auflisten").set_defaults(fn=cmd_models)
    p = sub.add_parser("download", help="Modell herunterladen")
    p.add_argument("model")
    p.set_defaults(fn=cmd_download)
    p = sub.add_parser("index", help="Ordner indexieren")
    p.add_argument("folder")
    p.add_argument("-m", "--model", default=catalog.DEFAULT_MODEL_ID)
    p.add_argument("-t", "--threads", type=int)
    p.add_argument("-o", "--export", help="danach als .lumenindex exportieren (Datei oder Ordner)")
    p.set_defaults(fn=cmd_index)
    p = sub.add_parser("export", help="Indexierungsdatei aus dem Zwischenspeicher schreiben")
    p.add_argument("folder")
    p.add_argument("output")
    p.add_argument("-m", "--model", default=catalog.DEFAULT_MODEL_ID)
    p.set_defaults(fn=cmd_export)
    p = sub.add_parser("inspect", help="Indexierungsdatei ansehen")
    p.add_argument("file")
    p.set_defaults(fn=cmd_inspect)

    args = parser.parse_args(argv)
    if args.cmd in (None, "gui"):
        from .gui import run

        return run()
    try:
        return args.fn(args)
    except Cancelled:
        return 130
    except (OSError, ValueError) as e:
        print(f"Fehler: {e}", file=sys.stderr)
        return 1
