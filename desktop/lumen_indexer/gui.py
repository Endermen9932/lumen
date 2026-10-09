"""GTK 4 / libadwaita window (Ubuntu 24.04 ships both)."""
from __future__ import annotations

import threading
from pathlib import Path

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")
from gi.repository import Adw, Gio, GLib, Gtk  # noqa: E402

from . import APP_NAME, __version__, catalog, faces, gpu  # noqa: E402
from .engine import available_accelerator, cpu_threads  # noqa: E402
from .exporter import default_faces_filename, default_filename, export_faces, export_index  # noqa: E402
from .imaging import scan_images  # noqa: E402
from .indexer import Progress, run_index, store_key  # noqa: E402
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
        self.face_models = faces.FaceModelStore(self.models)
        self.store = Store()
        self.face_model = faces.face_model(faces.DEFAULT_FACE_MODEL_ID)
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
        g2 = Adw.PreferencesGroup(title="2 · KI-Suche", description="Dasselbe Modell muss später auch auf dem Handy installiert sein, um zu suchen.")
        self.clip_switch = Adw.SwitchRow(title="Fotos für die KI-Suche analysieren", active=True)
        self.clip_switch.connect("notify::active", lambda *_: self._refresh_buttons())
        g2.add(self.clip_switch)
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
        self.quality_row = Adw.ComboRow(
            title="Qualität",
            subtitle="„Hoch“ analysiert jedes Foto zusätzlich gespiegelt und mittelt – gleicher Vektorraum wie das Handy, etwas robuster, doppelte Zeit",
        )
        self.quality_row.set_model(Gtk.StringList.new(["Standard (wie Handy)", "Hoch"]))
        self.quality_row.connect("notify::selected", lambda *_: self._refresh_cache_info())
        g2.add(self.quality_row)
        self.dl_bar = Gtk.ProgressBar(visible=False, margin_top=6)
        g2.add(self.dl_bar)
        page.add(g2)

        # Faces
        gf = Adw.PreferencesGroup(
            title="3 · Gesichter",
            description="Gleiche Modelle wie in der App (Personen → Erkennungsqualität). Das Handy gruppiert die Gesichter vom PC "
                        "zusammen mit denen neuer Fotos, die es selbst erkennt.",
        )
        self.faces_switch = Adw.SwitchRow(title="Gesichter erkennen", active=False)
        self.faces_switch.connect("notify::active", lambda *_: self._refresh_face_model())
        gf.add(self.faces_switch)
        self.face_model_row = Adw.ComboRow(title="Erkennungsqualität")
        self.face_model_row.set_model(Gtk.StringList.new([f"{m.tier} · {m.name}" for m in faces.FACE_MODELS]))
        self.face_model_row.set_selected([m.id for m in faces.FACE_MODELS].index(faces.DEFAULT_FACE_MODEL_ID))
        self.face_model_row.connect("notify::selected", self._face_model_changed)
        gf.add(self.face_model_row)
        self.face_quality_row = Adw.ComboRow(
            title="Detektor",
            subtitle="„Hoch“: großer SCRFD-10G-Detektor mit 1024 px – findet auch kleine Gesichter in Gruppenfotos. "
                     "Die Wiedererkennung bleibt die des Handys, daher passt alles zusammen.",
        )
        self.face_quality_row.set_model(Gtk.StringList.new(["Wie Handy", "Hoch"]))
        self.face_quality_row.connect("notify::selected", lambda *_: self._refresh_face_model())
        gf.add(self.face_quality_row)
        self.face_state = Adw.ActionRow(title="Status")
        self.face_dl_btn = Gtk.Button(label="Herunterladen", valign=Gtk.Align.CENTER)
        self.face_dl_btn.add_css_class("suggested-action")
        self.face_dl_btn.connect("clicked", self._download_face_model)
        self.face_state.add_suffix(self.face_dl_btn)
        gf.add(self.face_state)
        page.add(gf)

        # 3 · Performance
        g3 = Adw.PreferencesGroup(title="4 · Leistung")
        self.threads_row = Adw.SpinRow.new_with_range(1, cpu_threads(), 1)
        self.threads_row.set_title("CPU-Threads")
        self.threads_row.set_subtitle(f"Dein Rechner hat {cpu_threads()} Threads – alle zu nutzen ist am schnellsten")
        self.threads_row.set_value(cpu_threads())
        g3.add(self.threads_row)
        self.device_row = Adw.ActionRow(title="Rechenhardware")
        g3.add(self.device_row)
        self.gpu_row = Adw.ActionRow(title="NVIDIA-GPU-Beschleunigung (CUDA)")
        self.gpu_btn = Gtk.Button(valign=Gtk.Align.CENTER)
        self.gpu_btn.connect("clicked", self._gpu_clicked)
        self.gpu_row.add_suffix(self.gpu_btn)
        self.gpu_switch = Gtk.Switch(valign=Gtk.Align.CENTER, active=gpu.enabled())
        self.gpu_switch.connect("notify::active", self._gpu_toggled)
        self.gpu_row.add_suffix(self.gpu_switch)
        g3.add(self.gpu_row)
        self.gpu_log = Gtk.Label(label="", xalign=0, wrap=True, visible=False, margin_top=6, selectable=True)
        self.gpu_log.add_css_class("dim-label")
        self.gpu_log.add_css_class("caption")
        g3.add(self.gpu_log)
        page.add(g3)
        self.nvidia = gpu.detect_nvidia()
        self.gpu_busy = False
        self._refresh_gpu()

        # 4 · Run
        g4 = Adw.PreferencesGroup(title="5 · Indexieren")
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
            title="6 · Exportieren",
            description="Wähle, wo die Datei gespeichert wird, kopiere sie aufs Handy (USB, Nextcloud, …) und importiere sie in Lumen "
                        "(KI-Modelle → Indexierung vom PC). Du kannst jederzeit exportieren – auch einen unvollständigen Stand.",
        )
        self.cached_row = Adw.ActionRow(title="KI-Suche", subtitle="–")
        self.export_btn = Gtk.Button(label="Exportieren …", valign=Gtk.Align.CENTER)
        self.export_btn.connect("clicked", self._export)
        self.cached_row.add_suffix(self.export_btn)
        g5.add(self.cached_row)
        self.faces_row = Adw.ActionRow(title="Gesichter", subtitle="–")
        self.export_faces_btn = Gtk.Button(label="Exportieren …", valign=Gtk.Align.CENTER)
        self.export_faces_btn.connect("clicked", self._export_faces)
        self.faces_row.add_suffix(self.export_faces_btn)
        g5.add(self.faces_row)
        page.add(g5)

        self._refresh_model()
        self._refresh_face_model()
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

    @property
    def _high_quality(self) -> bool:
        return self.quality_row.get_selected() == 1

    @property
    def _face_hq(self) -> bool:
        return self.face_quality_row.get_selected() == 1

    def _refresh_cache_info(self) -> None:
        if not hasattr(self, "faces_row"):
            return
        if self._root_key:
            n = self.store.count(self._root_key, store_key(self.model, self._high_quality))
            q = " (Qualität Hoch)" if self._high_quality else ""
            self.cached_row.set_subtitle(f"{_n(n)} Fotos mit {self.model.name}{q}")
            photos, found = self.store.face_counts(self._root_key, self.face_model.id)
            self.faces_row.set_subtitle(f"{_n(photos)} Fotos gescannt, {_n(found)} Gesichter ({self.face_model.tier})")
        else:
            self.cached_row.set_subtitle("–")
            self.faces_row.set_subtitle("–")
        self._refresh_buttons()

    def _refresh_buttons(self) -> None:
        if not hasattr(self, "export_faces_btn"):
            return
        clip_on = self.clip_switch.get_active()
        faces_on = self.faces_switch.get_active()
        ready = (not clip_on or self.models.is_installed(self.model)) and \
                (not faces_on or self.face_models.is_installed(self.face_model, self._face_hq)) and (clip_on or faces_on)
        cached = bool(self._root_key) and self.store.count(self._root_key, store_key(self.model, self._high_quality)) > 0
        faces_cached = bool(self._root_key) and self.store.face_counts(self._root_key, self.face_model.id)[0] > 0
        self.start_btn.set_sensitive(self.busy or (self.folder is not None and ready))
        self.export_btn.set_sensitive(not self.busy and cached)
        self.export_faces_btn.set_sensitive(not self.busy and faces_cached)
        for w in (self.model_row, self.threads_row, self.quality_row, self.clip_switch, self.faces_switch, self.face_model_row, self.face_quality_row):
            w.set_sensitive(not self.busy)
        self.face_model_row.set_visible(faces_on)
        self.face_quality_row.set_visible(faces_on)
        self.face_state.set_visible(faces_on)

    # ------------------------------------------------------------------ gpu
    def _refresh_gpu(self) -> None:
        accel = None
        try:
            accel = available_accelerator()
        except Exception:  # noqa: BLE001
            pass
        self.device_row.set_subtitle({
            None: "CPU – alle Kerne",
            "CUDAExecutionProvider": "NVIDIA-GPU (CUDA) wird verwendet",
            "ROCMExecutionProvider": "AMD-GPU (ROCm) wird verwendet",
            "OpenVINOExecutionProvider": "Intel (OpenVINO) wird verwendet",
        }.get(accel, accel))
        flavor = gpu.installed_flavor()
        if self.nvidia is None and not flavor:
            self.gpu_row.set_subtitle("Keine NVIDIA-GPU mit Treiber gefunden. Mit dem NVIDIA-Treiber (z. B. über „Zusätzliche Treiber“) wird das hier verfügbar.")
            self.gpu_btn.set_visible(False)
            self.gpu_switch.set_visible(False)
            return
        gpu_name = f"{self.nvidia.name} · Treiber {self.nvidia.driver}" if self.nvidia else "NVIDIA-GPU"
        if self.gpu_busy:
            self.gpu_row.set_subtitle(f"{gpu_name} · wird installiert ({gpu.DOWNLOAD_HINT}) …")
            self.gpu_btn.set_label("Abbrechen")
            self.gpu_btn.set_visible(True)
            self.gpu_switch.set_visible(False)
        elif flavor:
            active = accel == "CUDAExecutionProvider"
            hint = "aktiv" if active else ("Neustart nötig" if gpu.enabled() else "aus")
            self.gpu_row.set_subtitle(f"{gpu_name} · {flavor} installiert · {hint}")
            self.gpu_btn.set_label("Entfernen")
            self.gpu_btn.set_visible(True)
            self.gpu_switch.set_visible(True)
        else:
            flavor_needed = gpu.flavor_for(self.nvidia.driver_major) if self.nvidia else None
            if flavor_needed is None:
                self.gpu_row.set_subtitle(f"{gpu_name} – der Treiber ist zu alt, mindestens Version 525 wird gebraucht.")
                self.gpu_btn.set_visible(False)
            else:
                self.gpu_row.set_subtitle(f"{gpu_name} · lädt ONNX Runtime mit {flavor_needed.label} ({gpu.DOWNLOAD_HINT}), kein Root nötig")
                self.gpu_btn.set_label("Installieren")
                self.gpu_btn.set_visible(True)
            self.gpu_switch.set_visible(False)

    def _gpu_clicked(self, *_):
        if self.gpu_busy:
            self.gpu_cancel.set()
            return
        if gpu.installed_flavor():
            gpu.remove()
            self._say("GPU-Laufzeit entfernt – nach einem Neustart rechnet Lumen Indexer wieder nur mit der CPU")
            self._refresh_gpu()
            return
        self.gpu_busy = True
        self.gpu_cancel = threading.Event()
        self.gpu_log.set_visible(True)
        self.gpu_log.set_label("Starte pip …")
        self._refresh_gpu()

        def work():
            try:
                f = gpu.install(lambda line: self._ui(self.gpu_log.set_label, line[-300:]), self.gpu_cancel)
                self._ui(self._gpu_done, f"{f.label} installiert – bitte Lumen Indexer neu starten, dann rechnet er auf der GPU.")
            except Exception as e:  # noqa: BLE001
                self._ui(self._gpu_done, f"GPU-Installation fehlgeschlagen: {e}")

        threading.Thread(target=work, daemon=True).start()

    def _gpu_done(self, message: str) -> None:
        self.gpu_busy = False
        self._say(message)
        self.gpu_log.set_label(message)
        self.gpu_switch.set_active(gpu.enabled())
        self._refresh_gpu()

    def _gpu_toggled(self, *_):
        if gpu.installed_flavor() and self.gpu_switch.get_active() != gpu.enabled():
            gpu.set_enabled(self.gpu_switch.get_active())
            self._say("Wird beim nächsten Start von Lumen Indexer wirksam")
            self._refresh_gpu()

    # ------------------------------------------------------------------ faces
    def _face_model_changed(self, *_):
        self.face_model = faces.FACE_MODELS[self.face_model_row.get_selected()]
        self._refresh_face_model()

    def _refresh_face_model(self) -> None:
        if not hasattr(self, "face_state"):
            return
        m, hq = self.face_model, self._face_hq
        missing = self.face_models.download_bytes(m, hq)
        if missing:
            self.face_state.set_subtitle(f"Download nötig: {_bytes(missing)}")
        else:
            self.face_state.set_subtitle("Installiert" + (" · mit großem Detektor" if hq else ""))
        self.face_dl_btn.set_visible(missing > 0)
        self._refresh_cache_info()

    def _download_face_model(self, *_):
        m, hq = self.face_model, self._face_hq
        self.busy = True
        self.cancel.clear()
        self.face_dl_btn.set_sensitive(False)
        self._refresh_buttons()

        def work():
            try:
                self.face_models.download(m, hq, lambda d, t: self._ui(self.face_state.set_subtitle, f"Lade … {_bytes(d)} von {_bytes(t)}"), self.cancel)
                self._ui(self._face_dl_done, None)
            except Cancelled:
                self._ui(self._face_dl_done, "Download abgebrochen")
            except Exception as e:  # noqa: BLE001
                self._ui(self._face_dl_done, f"Download fehlgeschlagen: {e}")

        threading.Thread(target=work, daemon=True).start()

    def _face_dl_done(self, error: str | None) -> None:
        self.busy = False
        self.face_dl_btn.set_sensitive(True)
        if error:
            self._say(error)
        self._refresh_face_model()

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
        clip_on, faces_on, hq = self.clip_switch.get_active(), self.faces_switch.get_active(), self._high_quality
        face_model, face_hq = self.face_model, self._face_hq

        def work():
            summary = face_summary = None
            try:
                if clip_on:
                    summary = run_index(folder, model, self.store, self.models,
                                        lambda p: self._ui(self._progress, p), self.cancel, threads, high_quality=hq)
                if faces_on and not self.cancel.is_set():
                    face_summary = faces.run_faces(folder, face_model, face_hq, self.store, self.face_models,
                                                   lambda p: self._ui(self._face_progress, p), self.cancel, threads)
                self._ui(self._index_done, summary, None, face_summary)
            except Exception as e:  # noqa: BLE001
                self._ui(self._index_done, None, str(e), None)

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

    def _face_progress(self, p: "faces.FaceProgress") -> None:
        if p.phase == "scan":
            self.run_label.set_label(f"Gesichter: suche Fotos … {_n(p.found)}")
        elif p.phase == "load":
            self.run_bar.pulse()
            self.run_label.set_label(f"Gesichter: {_n(p.total)} neue Fotos – Modell wird geladen …")
        else:
            self.run_bar.set_fraction(p.done / p.total if p.total else 1.0)
            speed = f"{p.per_second:.1f}".replace(".", ",")
            self.run_label.set_label(
                f"Gesichter: {_n(p.done)} von {_n(p.total)} · {_n(p.faces)} gefunden · {speed} Fotos/s · noch ca. {_eta(p.eta_seconds)} · {p.device}"
            )

    def _index_done(self, summary, error: str | None, face_summary=None) -> None:
        self.busy = False
        self.start_btn.set_label("Indexierung starten")
        self.start_btn.remove_css_class("destructive-action")
        self.start_btn.add_css_class("suggested-action")
        if error:
            self.run_label.set_label(f"Fehler: {error}")
        elif summary is None and face_summary is not None:
            s = face_summary
            verb = "Angehalten – der Fortschritt bleibt gespeichert" if s.cancelled else "Fertig"
            self.run_bar.set_fraction(0 if s.cancelled else 1.0)
            self.run_label.set_label(
                f"{verb}: {_n(s.newly_scanned)} Fotos neu gescannt, {_n(s.faces)} Gesichter, {_n(s.already_cached)} schon im Zwischenspeicher · {_eta(s.seconds)}"
            )
        elif summary is not None:
            verb = "Angehalten – der Fortschritt bleibt gespeichert" if summary.cancelled else "Fertig"
            self.run_bar.set_fraction(0 if summary.cancelled and not summary.newly_indexed else self.run_bar.get_fraction())
            if not summary.cancelled:
                self.run_bar.set_fraction(1.0)
            failed = f", {_n(summary.failed)} nicht lesbar" if summary.failed else ""
            extra = ""
            if face_summary is not None:
                extra = f" · Gesichter: {_n(face_summary.newly_scanned)} Fotos, {_n(face_summary.faces)} gefunden"
            self.run_label.set_label(
                f"{verb}: {_n(summary.newly_indexed)} neu indexiert, {_n(summary.already_cached)} schon im Zwischenspeicher"
                f"{failed} · {_eta(summary.seconds)}{extra}"
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
        root, model, key = self._root_key, self.model, store_key(self.model, self._high_quality)
        self.busy = True
        self._refresh_buttons()
        self.run_label.set_label("Exportiere …")

        def work():
            try:
                n = export_index(self.store, root, model, target, key=key)
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

    def _export_faces(self, *_):
        dialog = Gtk.FileDialog(title="Gesichter speichern", initial_name=default_faces_filename(self.face_model.id, self.folder.name))
        flt = Gtk.FileFilter()
        flt.set_name("Lumen-Indexierung (*.lumenindex)")
        flt.add_pattern("*.lumenindex")
        filters = Gio.ListStore.new(Gtk.FileFilter)
        filters.append(flt)
        dialog.set_filters(filters)
        dialog.save(self, None, self._export_faces_target)

    def _export_faces_target(self, dialog, result):
        try:
            gfile = dialog.save_finish(result)
        except GLib.Error:
            return
        target = Path(gfile.get_path())
        if target.suffix != ".lumenindex":
            target = target.with_name(target.name + ".lumenindex")
        root, model = self._root_key, self.face_model
        self.busy = True
        self._refresh_buttons()
        self.run_label.set_label("Exportiere Gesichter …")

        def work():
            try:
                n, f = export_faces(self.store, root, model, target)
                self._ui(self._export_faces_done, target, n, f, None)
            except Exception as e:  # noqa: BLE001
                self._ui(self._export_faces_done, target, 0, 0, str(e))

        threading.Thread(target=work, daemon=True).start()

    def _export_faces_done(self, target: Path, n: int, f: int, error: str | None) -> None:
        self.busy = False
        self._refresh_buttons()
        if error:
            self.run_label.set_label(f"Export fehlgeschlagen: {error}")
            return
        self.run_label.set_label(f"{_n(n)} Fotos mit {_n(f)} Gesichtern exportiert: {target}")
        self._say("Gesichter gespeichert – jetzt aufs Handy kopieren und in Lumen importieren")

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
