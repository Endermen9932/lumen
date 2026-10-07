# Lumen Indexer (Ubuntu 24.04)

Berechnet den **KI-Suchindex** der Lumen-Fotos-App auf dem PC. Dort geht das deutlich schneller als auf dem
Handy (mehr Kerne, höhere Taktraten, optional GPU) – das Handy muss die Bilder dann nicht mehr selbst analysieren.

## So geht's

1. Fotos vom Handy auf den PC kopieren (USB, Nextcloud, …) – **mit den Original-Dateinamen**.
2. **Lumen Indexer** starten, den Ordner mit den Fotos wählen, ein Modell wählen (wird beim ersten Mal geladen)
   und **Indexierung starten**. Der Fortschritt bleibt gespeichert – Anhalten und später Weitermachen ist okay,
   ebenso das spätere Hinzufügen neuer Fotos in denselben Ordner.
3. **Exportieren …** schreibt eine `.lumenindex`-Datei. Die aufs Handy kopieren.
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

## Kommandozeile

```bash
lumen-indexer models                                  # Modelle und ihr Status
lumen-indexer index ~/Fotos -m siglip2-b16-256 -o ~/  # indexieren und gleich exportieren
lumen-indexer export ~/Fotos ~/index.lumenindex -m siglip2-b16-256
lumen-indexer inspect ~/index.lumenindex
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

Referenzdatei für beide Seiten: `app/src/test/resources/sample.lumenindex` (erzeugt von `tests/make_fixture.py`,
gelesen von `IndexImportTest` in der Android-App).

## Entwicklung

```bash
python3.12 -m venv --system-site-packages .venv && .venv/bin/pip install -r requirements.txt pytest
.venv/bin/python -m pytest tests
.venv/bin/python -m lumen_indexer            # Fenster (braucht python3-gi, gir1.2-gtk-4.0, gir1.2-adw-1)
sudo apt install python3-venv && packaging/build_deb.sh 1.0.0 .   # .deb bauen
```
