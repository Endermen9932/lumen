"""The AI models, identical (same ids, same preprocessing) to ModelCatalog.kt of the Android app.

The desktop uses the full-precision (fp32) vision towers: they are faster on x86 CPUs than the fp16
files the phone uses and give the same embeddings (cosine similarity > 0.9999). Only the vision
tower is needed here – the text tower stays on the phone, where the search happens.
"""
from __future__ import annotations

from dataclasses import dataclass

SQUASH = "squash"  # SigLIP: whole image is squashed to a square
CROP = "crop"  # CLIP: shortest edge to the input size, then centre crop

_HALF = (0.5, 0.5, 0.5)
_ZERO = (0.0, 0.0, 0.0)
_ONE = (1.0, 1.0, 1.0)


@dataclass(frozen=True)
class ModelFile:
    path: str
    size: int

    @property
    def name(self) -> str:
        return self.path.rsplit("/", 1)[-1]


@dataclass(frozen=True)
class AiModel:
    id: str
    name: str
    tier: str
    description: str
    repo: str
    files: tuple[ModelFile, ...]
    image_size: int
    resize_mode: str
    mean: tuple[float, float, float]
    std: tuple[float, float, float]
    dim: int
    multilingual: bool

    @property
    def vision_file(self) -> ModelFile:
        return self.files[0]

    @property
    def total_bytes(self) -> int:
        return sum(f.size for f in self.files)

    def url(self, file: ModelFile) -> str:
        return f"https://huggingface.co/{self.repo}/resolve/main/{file.path}"


MODELS: tuple[AiModel, ...] = (
    AiModel(
        id="mobileclip-s0", name="MobileCLIP S0", tier="Blitz",
        description="Winzig und extrem schnell. Versteht nur englische Suchbegriffe.",
        repo="Xenova/mobileclip_s0",
        files=(ModelFile("onnx/vision_model.onnx", 45_543_630),),
        image_size=256, resize_mode=CROP, mean=_ZERO, std=_ONE, dim=512, multilingual=False,
    ),
    AiModel(
        id="siglip2-b32-256", name="SigLIP 2 Base/32", tier="Schnell",
        description="Schnelle Indexierung, versteht Deutsch und über 100 weitere Sprachen.",
        repo="onnx-community/siglip2-base-patch32-256-ONNX",
        files=(ModelFile("onnx/vision_model.onnx", 378_478_689),),
        image_size=256, resize_mode=SQUASH, mean=_HALF, std=_HALF, dim=768, multilingual=True,
    ),
    AiModel(
        id="siglip2-b16-256", name="SigLIP 2 Base/16", tier="Ausgewogen",
        description="Der beste Kompromiss aus Tempo und Genauigkeit. Mehrsprachig.",
        repo="onnx-community/siglip2-base-patch16-256-ONNX",
        files=(ModelFile("onnx/vision_model.onnx", 371_992_072),),
        image_size=256, resize_mode=SQUASH, mean=_HALF, std=_HALF, dim=768, multilingual=True,
    ),
    AiModel(
        id="siglip2-l16-384", name="SigLIP 2 Large/16 @384", tier="Präzise",
        description="Hohe Auflösung und großes Modell: erkennt auch kleine Details und Text im Bild.",
        repo="onnx-community/siglip2-large-patch16-384-ONNX",
        files=(ModelFile("onnx/vision_model.onnx", 1_265_655_968),),
        image_size=384, resize_mode=SQUASH, mean=_HALF, std=_HALF, dim=1024, multilingual=True,
    ),
    AiModel(
        id="siglip2-so400m-384", name="SigLIP 2 So400m/14 @384", tier="Ultra",
        description="Formoptimiertes 400M-Modell. Sehr genaue Suche, braucht viel Zeit beim Indexieren.",
        repo="onnx-community/siglip2-so400m-patch14-384-ONNX",
        files=(ModelFile("onnx/vision_model.onnx", 1_713_485_119),),
        image_size=384, resize_mode=SQUASH, mean=_HALF, std=_HALF, dim=1152, multilingual=True,
    ),
    AiModel(
        id="siglip2-giant-384", name="SigLIP 2 Giant/16 @384", tier="Maximum",
        description="Über 1 Mrd. Parameter – die genaueste Suche. Braucht viel RAM (~8 GB) und Zeit.",
        repo="onnx-community/siglip2-giant-opt-patch16-384-ONNX",
        files=(
            ModelFile("onnx/vision_model.onnx", 900_295),
            ModelFile("onnx/vision_model.onnx_data", 4_654_639_104),
        ),
        image_size=384, resize_mode=SQUASH, mean=_HALF, std=_HALF, dim=1536, multilingual=True,
    ),
)

DEFAULT_MODEL_ID = "siglip2-b16-256"


def by_id(model_id: str) -> AiModel | None:
    return next((m for m in MODELS if m.id == model_id), None)
