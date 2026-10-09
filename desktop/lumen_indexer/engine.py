"""ONNX Runtime wrapper for the vision tower of one model."""
from __future__ import annotations

import os

import numpy as np

from . import gpu
from .catalog import AiModel
from .modelstore import ModelStore

# Accelerators we use when the installed onnxruntime build offers them (the pip default is CPU only;
# the NVIDIA build can be added from within the app, see gpu.py).
_ACCELERATORS = ("CUDAExecutionProvider", "ROCMExecutionProvider", "OpenVINOExecutionProvider")


def _ort():
    gpu.activate()
    import onnxruntime as ort

    gpu.preload()
    return ort


def available_accelerator() -> str | None:
    avail = list(_ort().get_available_providers())
    # The CUDA build lists CUDA even without GPU or libraries – only offer it when it can work.
    if "CUDAExecutionProvider" in avail and not gpu.cuda_usable():
        avail.remove("CUDAExecutionProvider")
    return next((p for p in _ACCELERATORS if p in avail), None)


def create_session(path: str, threads: int | None = None, use_gpu: bool = True):
    """An inference session on the GPU if possible, otherwise on all CPU cores.

    If the CUDA libraries cannot be loaded (driver too old, missing library) ONNX Runtime silently
    stays on the CPU – the caller sees that in session.get_providers().
    """
    ort = _ort()
    opts = ort.SessionOptions()
    opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    opts.intra_op_num_threads = max(1, threads or cpu_threads())
    opts.inter_op_num_threads = 1
    opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
    opts.log_severity_level = 3  # SCRFD at 1024 px warns about its 640 px default output shapes
    accel = available_accelerator() if use_gpu else None
    providers: list = ["CPUExecutionProvider"]
    if accel == "CUDAExecutionProvider":
        providers.insert(0, ("CUDAExecutionProvider", {"device_id": 0, "cudnn_conv_algo_search": "HEURISTIC"}))
    elif accel:
        providers.insert(0, accel)
    try:
        return ort.InferenceSession(path, opts, providers=providers)
    except Exception:  # noqa: BLE001 – e.g. out of GPU memory: the CPU always works
        if len(providers) == 1:
            raise
        return ort.InferenceSession(path, opts, providers=["CPUExecutionProvider"])


def device_label(provider: str) -> str:
    return {
        "CPUExecutionProvider": "CPU",
        "CUDAExecutionProvider": "NVIDIA-GPU (CUDA)",
        "ROCMExecutionProvider": "AMD-GPU (ROCm)",
        "OpenVINOExecutionProvider": "Intel (OpenVINO)",
    }.get(provider, provider)


def cpu_threads() -> int:
    return max(1, os.cpu_count() or 1)


def _pick_output(names: list[str], preferred: str = "image_embeds") -> str:
    """Same rule as EmbeddingEngine.pickOutput on Android so both produce the same vector."""
    if preferred in names:
        return preferred
    if "pooler_output" in names:
        return "pooler_output"
    ends = [n for n in names if n.endswith("embeds")]
    return ends[0] if ends else names[-1]


class Engine:
    def __init__(self, model: AiModel, store: ModelStore, threads: int | None = None, use_gpu: bool = True):
        self.model = model
        self.session = create_session(str(store.path(model)), threads, use_gpu)
        self.provider = self.session.get_providers()[0]
        self.accelerated = self.provider != "CPUExecutionProvider"
        self._input = self.session.get_inputs()[0].name
        self._output = _pick_output([o.name for o in self.session.get_outputs()])
        # Batching does not help the CPU (measured), but keeps a GPU busy.
        self.batch_size = 16 if self.accelerated else 1

    @property
    def device_label(self) -> str:
        return device_label(self.provider)

    def embed(self, batch: np.ndarray) -> np.ndarray:
        """Returns L2-normalised embeddings (N, dim) for a float32 NCHW batch."""
        out = self.session.run([self._output], {self._input: batch})[0]
        out = np.asarray(out, dtype=np.float32).reshape(batch.shape[0], -1)
        norms = np.linalg.norm(out, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        return out / norms
