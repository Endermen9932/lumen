"""GTK 4 / libadwaita window (Ubuntu 24.04 ships both)."""
from __future__ import annotations

import threading
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from . import APP_NAME, __version__, catalog  # noqa: E402
from .engine import available_accelerator, cpu_threads  # noqa: E402
from .exporter import default_filename, export_index  # noqa: E402
from .imaging import scan_images  # noqa: E402
from .indexer import Progress, run_index  # noqa: E402
from .modelstore import Cancelled, ModelStore  # noqa: E402
from .store import Store  # noqa: E402

APP_ID = "app.lumen.indexer"


def _bytes(n: float) -> str:
    return f"{n / 1e9:.1f} GB".replace(".", ",") if n >= 1e9 else f"{n / 1e6:.0f} MB"


def _n(n: int) -> str:
    """German thousands separator: 12.345"""
    return f"{n:,}".replace(",", ".")


def _eta(seconds: float) -> str:
    s = int(seconds)
    if s < 60:
        return f"{s} s"
    if s < 3600:
        return f"{s // 60} min"
    return f"{s // 3600} h {(s % 3600) // 60} min"


class MainWindow(Adw.ApplicationWindow):
    def __init__(self, app: Adw.Application):
        super().__init__(application=app, title=APP_NAME, default_width=680, default_height=940)
        self.models = ModelStore()
        self.store = Store()
        self.folder: Path | None = None
        self.model = catalog.by_id(catalog.DEFAULT_MODEL_ID)
        self.cancel = threading.Event()
        self.busy = False
        self.last_summary_text = ""

        view = Adw.ToolbarView()
        header = Adw.HeaderBar()
        about = Gio.SimpleAction.new("about", None)
        about.connect("activate", self._show_about)
        self.add_action(about)
        menu = Gio.Menu()
        menu.append("Über Lumen Indexer", "win.about")
        header.pack_end(Gtk.MenuButton(icon_name="open-menu-symbolic", menu_model=menu, primary=True))
        view.add_top_bar(header)
        self.toast = Adw.ToastOverlay()
        view.set_content(self.toast)
        self.set_content(view)

        page = Adw.PreferencesPage()
        self.toast.set_child(page)

        intro = Adw.PreferencesGroup(
            title="KI-Indexierung am PC",
            description="Kopiere die Fotos vom Handy auf diesen Rechner, wähle den Ordner und lass den PC die "
                        "Bilder analysieren. Die fertige Datei importierst du danach in der Lumen-App "
                        "(KI-Modelle → „Indexierung importieren“).",
        )
        page.add(intro)

        # 1 · Folder
        g1 = Adw.PreferencesGroup(title="1 · Fotos")
        self.folder_row = Adw.ActionRow(title="Ordner mit den Fotos", subtitle="Noch kein Ordner gewählt")
        self.folder_row.set_activatable(True)
        pick = Gtk.Button(label="Ordner wählen …", valign=Gtk.Align.CENTER)
        pick.add_css_class("suggested-action")
        pick.connect("clicked", self._pick_folder)
        self.folder_row.add_suffix(pick)
        self.folder_row.connect("activated", self._pick_folder)
        g1.add(self.folder_row)
        page.add(g1)

        # 2 · Model
        g2 = Adw.PreferencesGroup(title="2 · KI-Modell", description="Dasselbe Modell muss später auch auf dem Handy installiert sein, um zu suchen.")
        self.model_row = Adw.ComboRow(title="Modell")
        self._model_labels = [m.tier for m in catalog.MODELS]
        self.model_row.set_model(Gtk.StringList.new(self._model_labels))
        self.model_row.set_selected([m.id for m in catalog.MODELS].index(catalog.DEFAULT_MODEL_ID))
        self.model_row.connect("notify::selected", self._model_changed)
        g2.add(self.model_row)
        self.model_state = Adw.ActionRow(title="Status")
        self.download_btn = Gtk.Button(label="Herunterladen", valign=Gtk.Align.CENTER)
        self.download_btn.add_css_class("suggested-action")
        self.download_btn.connect("clicked", self._download_model)
        self.model_state.add_suffix(self.download_btn)
        g2.add(self.model_state)
        self.dl_bar = Gtk.ProgressBar(visible=False, margin_top=6)
        g2.add(self.dl_bar)
        page.add(g2)

        # 3 · Performance
        g3 = Adw.PreferencesGroup(title="3 · Leistung")
        self.threads_row = Adw.SpinRow.new_with_range(1, cpu_threads(), 1)
        self.threads_row.set_title("CPU-Threads")
        self.threads_row.set_subtitle(f"Dein Rechner hat {cpu_threads()} Threads – alle zu nutzen ist am schnellsten")
        self.threads_row.set_value(cpu_threads())
        g3.add(self.threads_row)
        accel = available_accelerator()
        self.device_row = Adw.ActionRow(title="Rechenhardware", subtitle={
            None: "CPU (keine GPU-Unterstützung in dieser ONNX-Runtime)",
            "CUDAExecutionProvider": "NVIDIA-GPU (CUDA) wird verwendet",
            "ROCMExecutionProvider": "AMD-GPU (ROCm) wird verwendet",
            "OpenVINOExecutionProvider": "Intel (OpenVINO) wird verwendet",
        }.get(accel, accel))
        g3.add(self.device_row)
        page.add(g3)

        # 4 · Run
        g4 = Adw.PreferencesGroup(title="4 · Indexieren")
        self.start_btn = Gtk.Button(label="Indexierung starten", halign=Gtk.Align.FILL, margin_top=4)
        self.start_btn.add_css_class("suggested-action")
        self.start_btn.add_css_class("pill")
        self.start_btn.connect("clicked", self._start_or_stop)
        g4.add(self.start_btn)
        self.run_bar = Gtk.ProgressBar(margin_top=12, show_text=False)
        g4.add(self.run_bar)
        self.run_label = Gtk.Label(label="", xalign=0, wrap=True, margin_top=6)
        self.run_label.add_css_class("dim-label")
        g4.add(self.run_label)
        page.add(g4)

        # 5 · Export
        g5 = Adw.PreferencesGroup(
            title="5 · Indexierungsdatei exportieren",
            description="Kopiere die Datei aufs Handy (USB, Nextcloud, …) und importiere sie in Lumen. "
                        "Du kannst jederzeit exportieren – auch einen unvollständigen Stand.",
        )
        self.cached_row = Adw.ActionRow(title="Bereits indexiert", subtitle="–")
        g5.add(self.cached_row)
        self.export_btn = Gtk.Button(label="Exportieren …", halign=Gtk.Align.FILL, margin_top=4)
        self.export_btn.add_css_class("pill")
        self.export_btn.connect("clicked", self._export)
        g5.add(self.export_btn)
        page.add(g5)

        self._refresh_model()
        self._refresh_buttons()

    # ------------------------------------------------------------------ helpers
    def _say(self, text: str) -> None:
        self.toast.add_toast(Adw.Toast.new(text))

    def _ui(self, fn, *args) -> None:
        GLib.idle_add(lambda: (fn(*args), False)[1])

    @property
    def _root_key(self) -> str | None:
        return str(self.folder.resolve()) if self.folder else None

    def _refresh_model(self) -> None:
        m = self.model
        installed = self.models.is_installed(m)
        langs = "Deutsch + 100 Sprachen" if m.multilingual else "nur Englisch"
        self.model_row.set_subtitle(f"{m.name} – {m.description}")
        if installed:
            self.model_state.set_subtitle(f"Installiert · {_bytes(m.total_bytes)} · {langs}")
        else:
            self.model_state.set_subtitle(f"Download nötig: {_bytes(m.total_bytes)} · {langs}")
        self.download_btn.set_visible(not installed)
        self._refresh_cache_info()
        self._refresh_buttons()

    def _refresh_cache_info(self) -> None:
        if self._root_key:
            n = self.store.count(self._root_key, self.model.id)
            self.cached_row.set_subtitle(f"{_n(n)} Fotos mit {self.model.name}")
        else:
            self.cached_row.set_subtitle("–")

    def _refresh_buttons(self) -> None:
        installed = self.models.is_installed(self.model)
        cached = bool(self._root_key) and self.store.count(self._root_key, self.model.id) > 0
        self.start_btn.set_sensitive(self.busy or (self.folder is not None and installed))
        self.export_btn.set_sensitive(not self.busy and cached)
        self.model_row.set_sensitive(not self.busy)
        self.threads_row.set_sensitive(not self.busy)

    # ------------------------------------------------------------------ folder
    def _pick_folder(self, *_):
        dialog = Gtk.FileDialog(title="Ordner mit den Fotos wählen")
        dialog.select_folder(self, None, self._folder_done)

    def _folder_done(self, dialog, result):
        try:
            gfile = dialog.select_folder_finish(result)
        except GLib.Error:
            return
        self.folder = Path(gfile.get_path())
        self.folder_row.set_title(self.folder.name or str(self.folder))
        self.folder_row.set_subtitle(f"{self.folder} · zähle Fotos …")
        self._refresh_model()
        threading.Thread(target=self._count_images, args=(self.folder,), daemon=True).start()

    def _count_images(self, folder: Path) -> None:
        n = sum(1 for _ in scan_images(folder))
        self._ui(self._set_count, folder, n)

    def _set_count(self, folder: Path, n: int) -> None:
        if folder == self.folder:
            self.folder_row.set_subtitle(f"{folder} · {_n(n)} Fotos gefunden")

    # ------------------------------------------------------------------ model
    def _model_changed(self, *_):
        self.model = catalog.MODELS[self.model_row.get_selected()]
        self._refresh_model()

    def _download_model(self, *_):
        model = self.model
        self.busy = True
        self.cancel.clear()
        self.download_btn.set_sensitive(False)
        self.dl_bar.set_visible(True)
        self.dl_bar.set_fraction(0)
        self._refresh_buttons()

        def work():
            try:
                self.models.download(model, lambda d, t: self._ui(self._dl_progress, d, t), self.cancel)
                self._ui(self._dl_done, None)
            except Cancelled:
                self._ui(self._dl_done, "Download abgebrochen")
            except Exception as e:  # noqa: BLE001
                self._ui(self._dl_done, f"Download fehlgeschlagen: {e}")

        threading.Thread(target=work, daemon=True).start()

    def _dl_progress(self, done: int, total: int) -> None:
        self.dl_bar.set_fraction(done / max(total, 1))
        self.model_state.set_subtitle(f"Lade … {_bytes(done)} von {_bytes(total)}")

    def _dl_done(self, error: str | None) -> None:
        self.busy = False
        self.dl_bar.set_visible(False)
        self.download_btn.set_sensitive(True)
        if error:
            self._say(error)
        self._refresh_model()

    # ------------------------------------------------------------------ indexing
    def _start_or_stop(self, *_):
        if self.busy:
            self.cancel.set()
            self.start_btn.set_sensitive(False)
            self.start_btn.set_label("Wird angehalten …")
            return
        if not self.folder:
            return
        self.busy = True
        self.cancel.clear()
        self.start_btn.set_label("Anhalten")
        self.start_btn.remove_css_class("suggested-action")
        self.start_btn.add_css_class("destructive-action")
        self.run_bar.set_fraction(0)
        self.run_label.set_label("Suche Fotos …")
        self._refresh_buttons()
        folder, model, threads = self.folder, self.model, int(self.threads_row.get_value())

        def work():
            try:
                summary = run_index(folder, model, self.store, self.models,
                                    lambda p: self._ui(self._progress, p), self.cancel, threads)
                self._ui(self._index_done, summary, None)
            except Exception as e:  # noqa: BLE001
                self._ui(self._index_done, None, str(e))

        threading.Thread(target=work, daemon=True).start()

    def _progress(self, p: Progress) -> None:
        if p.phase == "scan":
            self.run_label.set_label(f"Suche Fotos … {_n(p.found)}")
        elif p.phase == "load":
            self.run_bar.pulse()
            self.run_label.set_label(f"{_n(p.total)} neue Fotos – Modell wird geladen …")
        else:
            self.run_bar.set_fraction(p.done / p.total if p.total else 1.0)
            speed = f"{p.per_second:.1f}".replace(".", ",")
            self.run_label.set_label(
                f"{_n(p.done)} von {_n(p.total)} · {speed} Fotos/s · noch ca. {_eta(p.eta_seconds)} · {p.device}"
                + (f" · {p.failed} nicht lesbar" if p.failed else "")
            )

    def _index_done(self, summary, error: str | None) -> None:
        self.busy = False
        self.start_btn.set_label("Indexierung starten")
        self.start_btn.remove_css_class("destructive-action")
        self.start_btn.add_css_class("suggested-action")
        if error:
            self.run_label.set_label(f"Fehler: {error}")
        else:
            verb = "Angehalten – der Fortschritt bleibt gespeichert" if summary.cancelled else "Fertig"
            self.run_bar.set_fraction(0 if summary.cancelled and not summary.newly_indexed else self.run_bar.get_fraction())
            if not summary.cancelled:
                self.run_bar.set_fraction(1.0)
            failed = f", {_n(summary.failed)} nicht lesbar" if summary.failed else ""
            self.run_label.set_label(
                f"{verb}: {_n(summary.newly_indexed)} neu indexiert, {_n(summary.already_cached)} schon im Zwischenspeicher"
                f"{failed} · {_eta(summary.seconds)}"
            )
        self._refresh_cache_info()
        self._refresh_buttons()

    # ------------------------------------------------------------------ export
    def _export(self, *_):
        dialog = Gtk.FileDialog(title="Indexierungsdatei speichern",
                                initial_name=default_filename(self.model, self.folder.name))
        flt = Gtk.FileFilter()
        flt.set_name("Lumen-Indexierung (*.lumenindex)")
        flt.add_pattern("*.lumenindex")
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(flt)
        dialog.set_filters(filters)
        dialog.save(self, None, self._export_target)

    def _export_target(self, dialog, result):
        try:
            gfile = dialog.save_finish(result)
        except GLib.Error:
            return
        target = Path(gfile.get_path())
        if target.suffix != ".lumenindex":
            target = target.with_name(target.name + ".lumenindex")
        root, model = self._root_key, self.model
        self.busy = True
        self._refresh_buttons()
        self.run_label.set_label("Exportiere …")

        def work():
            try:
                n = export_index(self.store, root, model, target)
                self._ui(self._export_done, target, n, None)
            except Exception as e:  # noqa: BLE001
                self._ui(self._export_done, target, 0, str(e))

        threading.Thread(target=work, daemon=True).start()

    def _export_done(self, target: Path, n: int, error: str | None) -> None:
        self.busy = False
        self._refresh_buttons()
        if error:
            self.run_label.set_label(f"Export fehlgeschlagen: {error}")
            return
        size = target.stat().st_size
        self.run_label.set_label(f"{_n(n)} Fotos exportiert ({_bytes(size)}): {target}")
        self._say("Indexierungsdatei gespeichert – jetzt aufs Handy kopieren und in Lumen importieren")

    def _show_about(self, *_):
        dialog = Adw.AboutDialog(
            application_name=APP_NAME, application_icon="lumen-indexer", version=__version__,
            developer_name="Lumen Photos",
            comments="Berechnet den KI-Suchindex der Lumen-Fotos-App auf dem PC – deutlich schneller als auf dem Handy.",
            website="https://github.com/Endermen9932/lumen", license_type=Gtk.License.MIT_X11,
        )
        dialog.present(self)

    def do_close_request(self) -> bool:  # noqa: N802
        self.cancel.set()
        return False


class App(Adw.Application):
    def __init__(self):
        super().__init__(application_id=APP_ID, flags=Gio.ApplicationFlags.DEFAULT_FLAGS)

    def do_activate(self):  # noqa: N802
        win = self.props.active_window or MainWindow(self)
        win.present()


def run() -> int:
    return App().run(None)
