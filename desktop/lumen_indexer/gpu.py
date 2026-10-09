"""NVIDIA GPU acceleration (CUDA) as an optional add-on.

The .deb ships the CPU build of ONNX Runtime – the CUDA build with its libraries is ~2.5 GB and
must match the installed NVIDIA driver. So the app installs it on request into its data folder
(~/.local/share/lumen-indexer/gpu) and puts that folder in front of the Python path before ONNX
Runtime is imported. No root rights, no system CUDA toolkit needed – only the NVIDIA driver.

  driver >= 580: onnxruntime-gpu 1.31 with CUDA 13
  driver >= 525: onnxruntime-gpu 1.26 with CUDA 12
"""
from __future__ import annotations

import os
import re
import shutil
import subprocess
import sys
import threading
from dataclasses import dataclass
from pathlib import Path
from typing import Callable

from .paths import data_dir

_activated = False


@dataclass(frozen=True)
class CudaFlavor:
    cuda: int
    min_driver: int
    packages: tuple[str, ...]

    @property
    def label(self) -> str:
        return f"CUDA {self.cuda}"


FLAVORS = (
    CudaFlavor(13, 580, (
        "onnxruntime-gpu==1.31.0",
        "nvidia-cuda-runtime~=13.0", "nvidia-cuda-nvrtc~=13.0", "nvidia-cublas~=13.0",
        "nvidia-cufft~=12.0", "nvidia-curand~=10.0", "nvidia-cudnn-cu13~=9.0",
    )),
    CudaFlavor(12, 525, (
        "onnxruntime-gpu==1.26.0",
        "nvidia-cuda-runtime-cu12~=12.0", "nvidia-cuda-nvrtc-cu12~=12.0", "nvidia-cublas-cu12~=12.0",
        "nvidia-cufft-cu12~=11.0", "nvidia-curand-cu12~=10.0", "nvidia-cudnn-cu12~=9.0",
    )),
)

DOWNLOAD_HINT = "ca. 2,5 GB"


def gpu_dir() -> Path:
    return data_dir() / "gpu"


def site_dir() -> Path:
    return gpu_dir() / "site"


def _disabled_flag() -> Path:
    return gpu_dir() / "disabled"


def installed() -> bool:
    return (site_dir() / "onnxruntime").is_dir()


def enabled() -> bool:
    return installed() and not _disabled_flag().exists() and not os.environ.get("LUMEN_INDEXER_CPU")


def set_enabled(on: bool) -> None:
    gpu_dir().mkdir(parents=True, exist_ok=True)
    if on:
        _disabled_flag().unlink(missing_ok=True)
    else:
        _disabled_flag().touch()


def activate() -> bool:
    """Puts the CUDA build in front of the CPU build. Must run before `import onnxruntime`."""
    global _activated
    if _activated:
        return True
    if not enabled() or "onnxruntime" in sys.modules:
        return False
    sys.path.insert(0, str(site_dir()))
    _activated = True
    return True


def preload() -> None:
    """Loads CUDA / cuDNN from the pip packages (onnxruntime >= 1.21 knows where they are)."""
    if not _activated:
        return
    try:
        import onnxruntime as ort

        if hasattr(ort, "preload_dlls"):
            ort.preload_dlls()
    except Exception:  # noqa: BLE001 – a missing library is reported when the session falls back
        pass


_usable: bool | None = None


def cuda_usable() -> bool:
    """True if an NVIDIA GPU is there and the CUDA runtime + cuDNN can be loaded (after preload)."""
    global _usable
    if _usable is None:
        import ctypes

        def loads(*names: str) -> bool:
            for n in names:
                try:
                    ctypes.CDLL(n)
                    return True
                except OSError:
                    continue
            return False

        _usable = detect_nvidia() is not None and loads("libcudart.so.13", "libcudart.so.12") and loads("libcudnn.so.9")
    return _usable


# ------------------------------------------------------------------ hardware

@dataclass
class NvidiaInfo:
    name: str
    driver: str

    @property
    def driver_major(self) -> int:
        m = re.match(r"(\d+)", self.driver)
        return int(m.group(1)) if m else 0


def detect_nvidia() -> NvidiaInfo | None:
    """The first NVIDIA GPU and the driver version, or None (no GPU or no NVIDIA driver)."""
    smi = shutil.which("nvidia-smi")
    if smi:
        try:
            out = subprocess.run(
                [smi, "--query-gpu=name,driver_version", "--format=csv,noheader"],
                capture_output=True, text=True, timeout=10,
            ).stdout.strip().splitlines()
            if out:
                name, _, driver = out[0].rpartition(",")
                return NvidiaInfo(name.strip(), driver.strip())
        except (OSError, subprocess.SubprocessError):
            pass
    proc = Path("/proc/driver/nvidia/version")
    if proc.is_file():
        m = re.search(r"Kernel Module\s+(?:for\s+\S+\s+)?(\d+\.\d+(?:\.\d+)?)", proc.read_text(errors="replace"))
        return NvidiaInfo("NVIDIA-GPU", m.group(1) if m else "")
    return None


def flavor_for(driver_major: int) -> CudaFlavor | None:
    return next((f for f in FLAVORS if driver_major >= f.min_driver), None)


# ------------------------------------------------------------------ install

class InstallError(Exception):
    pass


def install(on_line: Callable[[str], None], cancel: threading.Event | None = None, flavor: CudaFlavor | None = None) -> CudaFlavor:
    """pip-installs the CUDA build into gpu/site (atomically: a failed install leaves nothing behind)."""
    if flavor is None:
        info = detect_nvidia()
        if info is None:
            raise InstallError("Keine NVIDIA-GPU mit Treiber gefunden (nvidia-smi fehlt). Bitte zuerst den NVIDIA-Treiber installieren.")
        flavor = flavor_for(info.driver_major)
        if flavor is None:
            raise InstallError(f"Der NVIDIA-Treiber {info.driver} ist zu alt – mindestens Version 525 wird gebraucht.")
    gpu_dir().mkdir(parents=True, exist_ok=True)
    tmp = gpu_dir() / "site.part"
    shutil.rmtree(tmp, ignore_errors=True)
    # onnxruntime-gpu without dependencies: numpy, protobuf … come from the app's own environment.
    steps = [
        [flavor.packages[0], "--no-deps"],
        list(flavor.packages[1:]),
    ]
    for args in steps:
        cmd = [sys.executable, "-m", "pip", "install", "--disable-pip-version-check", "--no-cache-dir",
               "--progress-bar", "off", "--target", str(tmp), *args]
        on_line("$ pip install " + " ".join(args))
        proc = subprocess.Popen(cmd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        assert proc.stdout is not None
        for line in proc.stdout:
            on_line(line.rstrip())
            if cancel is not None and cancel.is_set():
                proc.terminate()
                proc.wait()
                shutil.rmtree(tmp, ignore_errors=True)
                raise InstallError("Abgebrochen")
        if proc.wait() != 0:
            shutil.rmtree(tmp, ignore_errors=True)
            raise InstallError("pip ist fehlgeschlagen – Details stehen im Protokoll.")
    shutil.rmtree(site_dir(), ignore_errors=True)
    tmp.replace(site_dir())
    (gpu_dir() / "flavor").write_text(flavor.label)
    set_enabled(True)
    return flavor


def remove() -> None:
    shutil.rmtree(gpu_dir(), ignore_errors=True)


def installed_flavor() -> str | None:
    f = gpu_dir() / "flavor"
    return f.read_text().strip() if f.is_file() and installed() else None


def size_on_disk() -> int:
    total = 0
    for root, _, files in os.walk(site_dir()):
        for name in files:
            try:
                total += os.path.getsize(os.path.join(root, name))
            except OSError:
                pass
    return total
