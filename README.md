# Lumen – Offline-Galerie für das Pixel 10 Pro

Eine Foto-App von Grund auf in Kotlin + Jetpack Compose mit **Material 3 Expressive**, gebaut für das
Pixel 10 Pro (Tensor G5, 16 GB RAM) – **ohne Google-Play-Dienste** und ohne Cloud.

## Funktionen

**Galerie**
- Timeline mit Tages-/Monatsgruppen, **Pinch-to-Zoom** (2–8 Spalten), Fast-Scroller mit Datumsblase
- Mehrfachauswahl per **Long-Press + Ziehen** mit Auto-Scroll, Gruppe auf einmal auswählen
- Shared-Element-Übergänge vom Raster in den Vollbild-Viewer
- Viewer mit Subsampling-Zoom (bis 12×), Wischen zum Schließen, Immersive-Modus, Floating Toolbar
- Video-Player (Media3), Info-Sheet mit EXIF (Kamera, Blende, ISO, GPS → Karten-App)
- Alben, Favoriten, Videos, Screenshots, Größte Dateien, Papierkorb (wiederherstellen/leeren)
- Erinnerungen („Vor X Jahren“), Verschieben in Alben, Teilen, Als Hintergrund, Editor
  (Filter, Helligkeit/Kontrast/Sättigung/Wärme/Tönung, Drehen/Spiegeln, speichert als Kopie mit EXIF)

**Speicher optimieren**
- Ganze Galerie mit einem Tipp auf **Full HD** (oder HD/QHD/4K) herunterskalieren
- Qualitätsstufen (Max 95 → Klein 60) + Feinregler, **Vorher/Nachher-Vergleich** mit Zoom
- Hochrechnung der Ersparnis per Stichproben, Chips mit Auflösung und Prozent
- **Ultra HDR (Gainmap) und EXIF inkl. GPS bleiben erhalten**, Datum bleibt in der Timeline
- Modi: Original ersetzen oder Kopie + Original in den Papierkorb (30 Tage wiederherstellbar)
- Läuft als Hintergrundjob mit Fortschritts-Benachrichtigung

**Videos komprimieren**
- Gleicher Stil wie beim Foto-Komprimierer: 720p/1080p/1440p/4K, fünf Qualitätsstufen, HEVC oder H.264
- Vorher/Nachher-Vergleich aus einem echten 3-Sekunden-Testclip inkl. Bitrate, Hochrechnung der Ersparnis
- Hardware-Encoder des Tensor G5 (Media3 Transformer, ohne Google-Dienste), HDR bleibt mit HEVC erhalten,
  Aufnahmedatum und GPS werden übernommen, läuft im Hintergrund mit Wakelock

**Personen (Gesichtserkennung, offline)**
- Neuer Tab „Personen“: häufige Gesichter werden automatisch gruppiert, Namen vergeben, zusammenführen, ausblenden
- Drei Qualitätsstufen: Schnell (buffalo_s, 16 MB), Ausgewogen (buffalo_l, 191 MB), Sehr gut (antelopev2, 278 MB)
- „Fotos prüfen“: bis zu 50 unsichere Treffer im Tinder-Stil wischen (rechts = ja, links = nein, Rückgängig)
- Suche nach Namen, auch kombiniert: „Paul“, „Paul Anna“, „Paul am Strand“
- Personenbild frei wählbar („Bild ändern“ auf der Personenseite)
- Mehrere Personen gleichzeitig auswählen (lang drücken oder „Alle auswählen“) und auf einmal ausblenden oder zusammenführen
- Namen hängen an der Person, nicht an der Datei – Komprimieren oder Neu-Scannen verliert nichts

**Suche mit Filtern**
- Feste, kombinierbare Filter: **Datum (von/bis), Typ (Fotos/Videos/Screenshots), Ort, Personen** (alle zusammen oder
  mindestens eine), **Album, Favoriten** – jeder Filter lässt sich von Hand einstellen
- Aus dem Suchtext werden **Filtervorschläge**: „Sommer 2026“ → 1.6.2026 – 31.8.2026, „Weihnachten vor zwei Jahren“,
  „März bis Mai 2024“, „seit 2020“, Personennamen, Orte, „Videos“, „Favoriten“ – ein Tipp setzt den Filter
- Optional ein **Sprachmodell auf dem Handy** (Qwen 2.5 1.5B oder 0.5B, offline) für freiere Formulierungen
  („Annas Hochzeit in München“). Es schlägt nur Werte für die festen Filter vor; jeder Vorschlag wird gegen die Anfrage geprüft
- **Orte offline**: GPS aus EXIF/Video-Metadaten, zugeordnet zu 64.000 Orten (GeoNames, CC BY 4.0) mit deutschen Namen,
  Regionen und Ländern – ohne Google-Dienste oder Internet

**KI-Indexierung und Gesichter am PC (Ubuntu 24.04)**
- Die Desktop-App **Lumen Indexer** (`.deb` bei jedem Release, Quellcode in [`desktop/`](desktop/README.md)) berechnet den
  KI-Suchindex **und die Gesichtserkennung** auf dem PC – mit allen Modellen, allen CPU-Kernen oder einer **NVIDIA-GPU** (CUDA)
- Qualität „Hoch“: größerer Gesichtsdetektor bzw. gespiegelte Zweitanalyse – im selben Vektorraum wie die Handy-Modelle,
  daher analysiert das Handy neue Fotos weiter mit seinen eigenen Modellen
- Fotos vom Handy auf den PC kopieren, Ordner wählen, indexieren, **`.lumenindex`-Dateien exportieren** (Speicherort frei wählbar)
- In der App unter KI-Modelle → **Indexierung vom PC** importieren: Zuordnung über Dateiname + Größe, bereits
  indexierte Fotos bleiben unverändert, der Rest wird weiter auf dem Handy indexiert

**Backup**
- Inkrementelles Backup der ganzen Galerie in einen frei wählbaren Ordner (USB-Stick, SD-Karte,
  Nextcloud/NAS über den Android-Dateimanager), Ordnerstruktur bleibt erhalten
- Optional jede Nacht automatisch beim Laden, Wiederherstellen fehlender Dateien mit einem Tipp
- Entwicklermodus: Modelle, Index, Gesichter und Einstellungen in einen **frei wählbaren Ordner** sichern und aus jedem
  Ordner wiederherstellen

**KI-Suche – 100 % offline (ONNX Runtime)**

| Stufe | Modell | Sprachen | Download |
|---|---|---|---|
| Blitz | MobileCLIP S0 | Englisch | 90 MB |
| Schnell | SigLIP 2 Base/32 | Deutsch + 100 | 0,7 GB |
| Ausgewogen | SigLIP 2 Base/16 | Deutsch + 100 | 0,7 GB |
| Präzise | SigLIP 2 Large/16 @384 | Deutsch + 100 | 1,4 GB |
| Ultra | SigLIP 2 So400m/14 @384 | Deutsch + 100 | 1,7 GB |
| Maximum | SigLIP 2 Giant/16 @384 | Deutsch + 100 | 3,2 GB |

- Einmaliger Download von Hugging Face (oder manueller Datei-Import), danach komplett offline
- Eigener Tokenizer in Kotlin (CLIP-BPE & Gemma/SentencePiece), getestet gegen Hugging Face
- Indexierung im Hintergrund, fortsetzbar, optional nur beim Laden; Index in Room (fp16)
- Suche nach Datum, auch kombiniert mit Text/Personen: „März 2024“, „12.03.2024“, „Strand Juli 2023“, „gestern“, „letzte Woche“
- Suche nach Text, „Ähnliche Fotos“, Entdecken-Kategorien, **ähnliche Serien & exakte Duplikate**
- Während Indexierung/Komprimierung bleibt das Display an, wird aber gedimmt (abschaltbar)

## Bauen

```bash
./gradlew assembleRelease      # APK: app/build/outputs/apk/release/
./gradlew testDebugUnitTest    # Tokenizer-Test braucht LUMEN_TOKENIZER_DIR (optional)
```

Jeder Push baut per GitHub Actions die APKs und veröffentlicht sie unter **Releases**. Signiert wird
immer mit einem festen Android-Debug-Key (`signing/debug.keystore`, `androiddebugkey` / `android`),
damit sich jede neue Version über die alte installieren lässt.
