"""ONNX Runtime wrapper for the vision tower of one model."""
from __future__ import annotations

import os

import numpy as np

from .catalog import AiModel
from .modelstore import ModelStore

# Accelerators we use when the installed onnxruntime build offers them (the pip default is CPU only).
_ACCELERATORS = ("CUDAExecutionProvider", "ROCMExecutionProvider", "OpenVINOExecutionProvider")


def available_accelerator() -> str | None:
    import onnxruntime as ort

    avail = ort.get_available_providers()
    return next((p for p in _ACCELERATORS if p in avail), None)


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
    def __init__(self, model: AiModel, store: ModelStore, threads: int | None = None):
        import onnxruntime as ort

        self.model = model
        accel = available_accelerator()
        opts = ort.SessionOptions()
        opts.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
        opts.intra_op_num_threads = max(1, threads or cpu_threads())
        opts.inter_op_num_threads = 1
        opts.execution_mode = ort.ExecutionMode.ORT_SEQUENTIAL
        providers = ([accel] if accel else []) + ["CPUExecutionProvider"]
        self.session = ort.InferenceSession(str(store.path(model)), opts, providers=providers)
        self.provider = self.session.get_providers()[0]
        self.accelerated = self.provider != "CPUExecutionProvider"
        self._input = self.session.get_inputs()[0].name
        self._output = _pick_output([o.name for o in self.session.get_outputs()])
        # Batching does not help the CPU (measured), but keeps a GPU busy.
        self.batch_size = 16 if self.accelerated else 1

    @property
    def device_label(self) -> str:
        return {
            "CPUExecutionProvider": "CPU",
            "CUDAExecutionProvider": "NVIDIA-GPU (CUDA)",
            "ROCMExecutionProvider": "AMD-GPU (ROCm)",
            "OpenVINOExecutionProvider": "Intel (OpenVINO)",
        }.get(self.provider, self.provider)

    def embed(self, batch: np.ndarray) -> np.ndarray:
        """Returns L2-normalised embeddings (N, dim) for a float32 NCHW batch."""
        out = self.session.run([self._output], {self._input: batch})[0]
        out = np.asarray(out, dtype=np.float32).reshape(batch.shape[0], -1)
        norms = np.linalg.norm(out, axis=1, keepdims=True)
        norms[norms == 0] = 1.0
        return out / norms
