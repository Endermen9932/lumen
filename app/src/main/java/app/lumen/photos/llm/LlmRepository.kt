package app.lumen.photos.llm

import app.lumen.photos.ai.ModelManager
import app.lumen.photos.data.settings.SettingsRepository
import app.lumen.photos.search.FilterSuggestion
import app.lumen.photos.search.ParseContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate
import kotlin.coroutines.coroutineContext

/**
 * The optional language model of the search: turns what was typed into *suggestions* for the
 * fixed filters (date, type, place, persons, album, favourites). Runs offline; the model is loaded
 * when the search needs it and released again after a few idle minutes (it takes 0.7–1.6 GB RAM).
 */
class LlmRepository(
    private val settings: SettingsRepository,
    private val models: ModelManager,
    private val scope: CoroutineScope,
) {
    val activeModel: StateFlow<LlmModel?> = settings.settings
        .map { LlmCatalog.byId(it.llmModelId) }
        .distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, LlmCatalog.byId(settings.current.llmModelId))

    private val mutex = Mutex()
    private var engine: LlmEngine? = null
    private var engineModel: LlmModel? = null
    private var engineThreads = 0
    private var idleJob: Job? = null

    fun enabledAndReady(): Boolean = activeModel.value?.let { models.isInstalled(it) } == true

    suspend fun setActive(model: LlmModel?) {
        settings.update { it.copy(llmModelId = model?.id) }
        release()
    }

    private suspend fun engine(model: LlmModel): LlmEngine = mutex.withLock {
        val threads = settings.current.aiThreads
        engine?.takeIf { engineModel == model && engineThreads == threads }?.let { return@withLock it }
        engine?.close()
        engine = null
        val tokenizer = QwenTokenizer.load(models.file(model, model.tokenizer), java.io.File(models.dir(model), "tokenizer.bin"))
        LlmEngine(models.file(model, model.model), tokenizer, threads).also {
            engine = it
            engineModel = model
            engineThreads = threads
        }
    }

    /** Loads the model and processes the fixed prompt beginning, so the first query is quick. */
    fun warmUp(ctx: ParseContext) {
        val model = activeModel.value ?: return
        if (!models.isInstalled(model)) return
        scope.launch(Dispatchers.Default) {
            runCatching { engine(model).generate(FilterPrompt.prefix(ctx, LocalDate.now()), "", 0) }
            scheduleRelease()
        }
    }

    suspend fun suggestFilters(query: String, ctx: ParseContext, today: LocalDate): List<FilterSuggestion> {
        val model = activeModel.value ?: return emptyList()
        if (!models.isInstalled(model) || query.isBlank()) return emptyList()
        return withContext(Dispatchers.Default) {
            val job = coroutineContext
            val answer = engine(model).generate(
                FilterPrompt.prefix(ctx, today),
                FilterPrompt.suffix(query),
                maxTokens = MAX_TOKENS,
                isCancelled = { !job.isActive },
                done = FilterPrompt::isComplete,
            )
            scheduleRelease()
            FilterPrompt.suggestions(answer, query, ctx, today)
        }
    }

    private fun scheduleRelease() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(IDLE_MS)
            release()
        }
    }

    suspend fun release() = mutex.withLock {
        engine?.close()
        engine = null
        engineModel = null
    }

    private companion object {
        const val MAX_TOKENS = 64
        const val IDLE_MS = 3 * 60_000L
    }
}
