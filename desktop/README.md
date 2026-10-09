# Lumen Indexer (Ubuntu 24.04)

Berechnet den **KI-Suchindex** und die **Gesichtserkennung** der Lumen-Fotos-App auf dem PC. Dort geht das deutlich schneller als auf dem
Handy (mehr Kerne, höhere Taktraten, optional GPU) – das Handy muss die Bilder dann nicht mehr selbst analysieren.

## So geht's

1. Fotos vom Handy auf den PC kopieren (USB, Nextcloud, …) – **mit den Original-Dateinamen**.
2. **Lumen Indexer** starten, den Ordner mit den Fotos wählen, KI-Suche und/oder Gesichter einschalten, Modelle wählen
   (werden beim ersten Mal geladen) und **Indexierung starten**. Der Fortschritt bleibt gespeichert – Anhalten und später Weitermachen ist okay,
   ebenso das spätere Hinzufügen neuer Fotos in denselben Ordner.
3. **Exportieren …** (KI-Suche bzw. Gesichter) schreibt je eine `.lumenindex`-Datei an einen Ort deiner Wahl. Die aufs Handy kopieren.
4. In Lumen: **Werkzeuge → KI-Suche → KI-Modelle → Indexierung vom PC → Datei importieren**.
   Das Modell muss auf dem Handy installiert sein, um damit zu suchen (der Import selbst geht auch ohne).

Die Zuordnung zu den Fotos auf dem Handy läuft über **Dateiname + Dateigröße**. Wurde ein Foto auf dem Handy seit
dem Kopieren verändert (z. B. mit „Speicher optimieren“ komprimiert), wird es bei Dateinamen mit Datum
(`PXL_20240312_…`) trotzdem über den Namen gefunden. Fotos, die nicht in der Datei sind, indexiert das Handy
wie gewohnt selbst.

## Installation

Die `.deb`-Datei steht bei jedem [Release](https://github.com/Endermen9932/lumen/releases):

```bash
sudo apt install ./lumen-indexer_*_amd64.deb
```

Danach ist **Lumen Indexer** im Anwendungsmenü; im Terminal geht auch `lumen-indexer`. Voraussetzung ist
Ubuntu 24.04 (Python 3.12, GTK 4, libadwaita) auf x86-64. Python-Bibliotheken liegen im Paket unter
`/opt/lumen-indexer`; Modelle und Zwischenspeicher unter `~/.local/share/lumen-indexer`.

## NVIDIA-GPU

Unter **Leistung → NVIDIA-GPU-Beschleunigung** installiert die App die CUDA-Version von ONNX Runtime samt CUDA- und
cuDNN-Bibliotheken (ca. 2,5 GB, aus PyPI) in `~/.local/share/lumen-indexer/gpu` – ohne Root-Rechte und ohne CUDA-Toolkit.
Nötig ist nur der NVIDIA-Treiber: ab Version 580 wird CUDA 13 verwendet, ab 525 CUDA 12. Nach einem Neustart rechnen
KI-Suche und Gesichtserkennung auf der GPU (mehrere Bilder pro Durchlauf); klappt etwas nicht, läuft alles automatisch
auf der CPU weiter. Kommandozeile: `lumen-indexer gpu status|install|on|off|remove`.

## Bessere Modelle, kompatibel zum Handy

Die Vektoren verschiedener Netze sind nicht vergleichbar – ein anderes, größeres Netz am PC würde einen Index erzeugen,
den das Handy mit seinem Modell nicht weiterführen kann. „Hoch“ verbessert deshalb nur, was den Vektorraum nicht ändert:

- **Gesichter → Detektor „Hoch“:** der große SCRFD-10G-Detektor (auch für „Schnell“, dessen Handy-Detektor SCRFD-500M ist)
  mit 1024 statt 640 px und Ausrichtung aus einem schärferen Bild – findet kleine Gesichter in Gruppenfotos. Die
  Wiedererkennung (das Netz, das die Vektoren erzeugt) ist die des Handys: dieselben Gesichter haben am PC mit „Hoch“ und
  auf dem Handy eine Ähnlichkeit von 0,90–0,99, das Handy gruppiert neue Fotos also einfach weiter.
- **KI-Suche → Qualität „Hoch“:** jedes Foto wird zusätzlich gespiegelt analysiert und beide Vektoren gemittelt
  (Test-Time-Augmentation) – gleiches Modell, etwas robuster, doppelte Rechenzeit.

## Kommandozeile

```bash
lumen-indexer models                                  # Modelle und ihr Status
lumen-indexer index ~/Fotos -m siglip2-b16-256 -o ~/  # indexieren und gleich exportieren
lumen-indexer export ~/Fotos ~/index.lumenindex -m siglip2-b16-256
lumen-indexer inspect ~/index.lumenindex
lumen-indexer index ~/Fotos -m siglip2-b16-256 --hq -o ~/  # Qualität „Hoch“
lumen-indexer faces ~/Fotos -m face-buffalo-l --hq -o ~/   # Gesichter erkennen und exportieren
lumen-indexer gpu install                             # NVIDIA-GPU einrichten
```

## Modelle

Dieselben sechs Modelle (gleiche IDs, gleiche Vorverarbeitung) wie in der App – von „Blitz“ (MobileCLIP S0) bis
„Maximum“ (SigLIP 2 Giant). Auf dem PC laufen die **fp32-Vision-Modelle** (schneller auf x86-CPUs als fp16, gleiche
Ergebnisse: Kosinus-Ähnlichkeit > 0,9999 zu den fp16-Modellen des Handys). Der Text-Teil der Suche bleibt auf
dem Handy. Die Datei `tests/test_core.py` prüft, dass der Katalog mit `ModelCatalog.kt` übereinstimmt.

Das Tempo hängt vom Rechner ab: Es wird jeder CPU-Kern genutzt (Bilder werden parallel dekodiert, JPEGs direkt
verkleinert). Eine GPU wird automatisch verwendet, wenn die installierte ONNX Runtime sie unterstützt
(CUDA, ROCm, OpenVINO) – die mitgelieferte Variante ist die CPU-Version.

## Dateiformat `.lumenindex`

ZIP mit drei Einträgen in dieser Reihenfolge (das Handy liest sie als Stream):

| Eintrag | Inhalt |
|---|---|
| `manifest.json` | `format`, `version`, `modelId`, `dim`, `count`, `encoding` (`fp16-le-l2`) … |
| `items.jsonl` | je Foto eine Zeile `{"n": Dateiname, "p": Unterordner, "s": Bytes, "m": mtime}` |
| `vectors.bin` | `count × dim` Half-Floats, little endian, L2-normiert – Vektor *i* gehört zu Zeile *i* |

**Gesichter** stehen im selben Container mit `"format": "lumen-faces"`: jede Zeile in `items.jsonl` hat zusätzlich
`"f": [[links, oben, rechts, unten, score], …]` (Box 0..1 im gedrehten Foto, auch leere Listen – die zählen auf dem
Handy als gescannt), `vectors.bin` enthält je Gesicht einen fp16-Vektor (512) in derselben Reihenfolge.

Referenzdateien für beide Seiten: `app/src/test/resources/sample.lumenindex` und `sample_faces.lumenindex` (erzeugt von `tests/make_fixture.py`,
gelesen von `IndexImportTest` in der Android-App).

## Entwicklung

```bash
python3.12 -m venv --system-site-packages .venv && .venv/bin/pip install -r requirements.txt pytest
.venv/bin/python -m pytest tests
.venv/bin/python -m lumen_indexer            # Fenster (braucht python3-gi, gir1.2-gtk-4.0, gir1.2-adw-1)
sudo apt install python3-venv && packaging/build_deb.sh 1.0.0 .   # .deb bauen
```
