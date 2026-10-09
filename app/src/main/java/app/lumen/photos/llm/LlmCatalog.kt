package app.lumen.photos.llm

import androidx.compose.runtime.Immutable
import app.lumen.photos.ai.DownloadableModel
import app.lumen.photos.ai.ModelFile

/** A small instruction-tuned language model (ONNX, 4-bit) used only to suggest search filters. */
@Immutable
data class LlmModel(
    override val id: String,
    val tier: String,
    val name: String,
    val description: String,
    override val repo: String,
    val model: ModelFile,
    val tokenizer: ModelFile,
    val ramMb: Int,
    val speed: Int,
    val accuracy: Int,
) : DownloadableModel {
    override val files: List<ModelFile> get() = listOf(model, tokenizer)

    override fun equals(other: Any?) = other is LlmModel && other.id == id
    override fun hashCode() = id.hashCode()
}

object LlmCatalog {
    val models = listOf(
        LlmModel(
            id = "llm-qwen2.5-1.5b",
            tier = "Genau",
            name = "Qwen 2.5 1.5B Instruct",
            description = "Versteht auch „Fotos von Annas Hochzeit in München“ oder „Weihnachten vor zwei Jahren“. Empfohlen.",
            repo = "onnx-community/Qwen2.5-1.5B-Instruct",
            model = ModelFile("onnx/model_q4f16.onnx", 1_221_878_940, "model.onnx"),
            tokenizer = ModelFile("tokenizer.json", 7_031_673, "tokenizer.json"),
            ramMb = 1600,
            speed = 3,
            accuracy = 4,
        ),
        LlmModel(
            id = "llm-qwen2.5-0.5b",
            tier = "Kompakt",
            name = "Qwen 2.5 0.5B Instruct",
            description = "Dreimal schneller und kleiner, irrt sich aber öfter – falsche Vorschläge werden aussortiert.",
            repo = "onnx-community/Qwen2.5-0.5B-Instruct",
            model = ModelFile("onnx/model_q4f16.onnx", 483_003_582, "model.onnx"),
            tokenizer = ModelFile("tokenizer.json", 7_031_673, "tokenizer.json"),
            ramMb = 700,
            speed = 5,
            accuracy = 2,
        ),
    )

    fun byId(id: String?): LlmModel? = models.firstOrNull { it.id == id }
}
