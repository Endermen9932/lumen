#!/usr/bin/env bash
# Builds lumen-indexer_<version>_<arch>.deb for Ubuntu 24.04 (Python 3.12, GTK 4, libadwaita).
#
#   packaging/build_deb.sh <version> [output-dir]
#
# The package ships its own virtualenv in /opt/lumen-indexer (numpy, Pillow, pillow-heif,
# onnxruntime from PyPI); only Python and GTK/libadwaita come from the system. Build it on
# Ubuntu 24.04 (or with PYTHON=/usr/bin/python3.12) so the wheels match the target Python.
set -euo pipefail

VERSION="${1:?usage: build_deb.sh <version> [output-dir]}"
OUT="$(realpath -m "${2:-.}")"
HERE="$(cd "$(dirname "$0")/.." && pwd)"
PYTHON="${PYTHON:-/usr/bin/python3}"
ARCH="$(dpkg --print-architecture)"
APP=/opt/lumen-indexer

PYVER="$("$PYTHON" -c 'import sys; print("%d.%d" % sys.version_info[:2])')"
if [ "$PYVER" != "3.12" ]; then
  echo "Python 3.12 is required (found $PYVER at $PYTHON) - set PYTHON=/usr/bin/python3.12" >&2
  exit 1
fi

STAGE="$(mktemp -d)"
trap 'rm -rf "$STAGE"' EXIT
ROOT="$STAGE/pkg"
mkdir -p "$ROOT$APP" "$ROOT/DEBIAN" "$ROOT/usr/bin" "$ROOT/usr/share/applications" \
         "$ROOT/usr/share/icons/hicolor/scalable/apps" "$ROOT/usr/share/doc/lumen-indexer"

echo "==> virtualenv"
# --system-site-packages: python3-gi (PyGObject) comes from apt and cannot be installed from PyPI.
"$PYTHON" -m venv --system-site-packages "$ROOT$APP/venv"
"$ROOT$APP/venv/bin/python" -m pip install --quiet --no-cache-dir --disable-pip-version-check -r "$HERE/requirements.txt"

echo "==> application"
SITE="$("$ROOT$APP/venv/bin/python" -c 'import sysconfig; print(sysconfig.get_paths()["purelib"])')"
cp -r "$HERE/lumen_indexer" "$SITE/lumen_indexer"
find "$SITE/lumen_indexer" -name __pycache__ -prune -exec rm -rf {} +
printf '# Written by packaging/build_deb.sh\n__version__ = "%s"\n' "$VERSION" > "$SITE/lumen_indexer/_version.py"
"$ROOT$APP/venv/bin/python" -m compileall -q "$SITE/lumen_indexer" >/dev/null

# The venv was created in a staging directory: point every script at its final location.
grep -rIl "$ROOT" "$ROOT$APP/venv/bin" "$ROOT$APP/venv/pyvenv.cfg" 2>/dev/null | xargs -r sed -i "s#$ROOT##g"
# Nothing in the package may still mention the staging directory.
if grep -rIl "$ROOT" "$ROOT$APP/venv/bin" "$ROOT$APP/venv/pyvenv.cfg" 2>/dev/null | grep -q .; then
  echo "staging path left in the venv" >&2; exit 1
fi

cat > "$ROOT/usr/bin/lumen-indexer" <<SCRIPT
#!/bin/sh
exec $APP/venv/bin/python -I -m lumen_indexer "\$@"
SCRIPT
chmod 755 "$ROOT/usr/bin/lumen-indexer"

install -m 644 "$HERE/packaging/app.lumen.indexer.desktop" "$ROOT/usr/share/applications/app.lumen.indexer.desktop"
install -m 644 "$HERE/packaging/lumen-indexer.svg" "$ROOT/usr/share/icons/hicolor/scalable/apps/lumen-indexer.svg"
install -m 644 "$HERE/README.md" "$ROOT/usr/share/doc/lumen-indexer/README.md"
cat > "$ROOT/usr/share/doc/lumen-indexer/copyright" <<COPY
Lumen Indexer - part of Lumen Photos (https://github.com/Endermen9932/lumen)

Bundled third-party software (numpy, Pillow, pillow-heif, onnxruntime) is under its own licenses,
see the *.dist-info directories below $APP/venv/lib/python3.12/site-packages/.
COPY

SIZE_KB="$(du -sk "$ROOT" | cut -f1)"
cat > "$ROOT/DEBIAN/control" <<CONTROL
Package: lumen-indexer
Version: $VERSION
Section: graphics
Priority: optional
Architecture: $ARCH
Installed-Size: $SIZE_KB
Depends: python3 (>= 3.12), python3 (<< 3.13), python3-gi, gir1.2-gtk-4.0, gir1.2-adw-1
Maintainer: Lumen Photos <noreply@users.noreply.github.com>
Homepage: https://github.com/Endermen9932/lumen
Description: AI search indexing for Lumen Photos on the PC
 Analyses a folder of photos copied from the phone with the same AI models as the Lumen
 Photos Android app (MobileCLIP / SigLIP 2) and exports a .lumenindex file that the app
 imports - much faster than indexing on the phone.
CONTROL

mkdir -p "$OUT"
DEB="$OUT/lumen-indexer_${VERSION}_${ARCH}.deb"
dpkg-deb --root-owner-group --build "$ROOT" "$DEB" >/dev/null
echo "==> $DEB ($(du -h "$DEB" | cut -f1))"
