package app.lumen.photos.ui.screens.models

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.ai.AiModel
import app.lumen.photos.ai.ImportResult
import app.lumen.photos.ai.ImportStatus
import app.lumen.photos.ai.IndexProgress
import app.lumen.photos.ai.ModelCatalog
import app.lumen.photos.container
import app.lumen.photos.work.BackgroundJobs
import app.lumen.photos.work.IndexImportWorker
import app.lumen.photos.ui.components.BackButton
import app.lumen.photos.ui.components.Dots
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.ShapeIcon
import app.lumen.photos.ui.navigation.LocalNavigator
import kotlinx.coroutines.launch

@Composable
fun ModelsScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val active by c.ai.activeModel.collectAsStateWithLifecycle()
    val installed by c.models.installed.collectAsStateWithLifecycle()
    val downloads by c.ai.downloads.collectAsStateWithLifecycle(initialValue = emptyMap())
    val failed by c.ai.failedDownloads.collectAsStateWithLifecycle(initialValue = emptyMap())
    val indexed by c.ai.indexedCount.collectAsStateWithLifecycle()
    val progress by c.ai.indexProgress.collectAsStateWithLifecycle(initialValue = null)
    val media by c.media.media.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val indexable = remember(media, settings.indexVideos) { media.count { settings.indexVideos || it.isImage } }
    var confirmDelete by remember { mutableStateOf<AiModel?>(null) }
    var importTarget by remember { mutableStateOf<AiModel?>(null) }

    val importStatus by c.ai.importStatus.collectAsStateWithLifecycle(initialValue = null)
    val indexFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            // Keep read access in case Android stops the process while the import runs.
            runCatching { context.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            c.ai.importIndex(uri)
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        val model = importTarget ?: return@rememberLauncherForActivityResult
        scope.launch {
            val n = c.models.import(model, uris) { uri -> displayName(context, uri) }
            Toast.makeText(context, "$n von ${model.files.size} Dateien importiert", Toast.LENGTH_LONG).show()
            if (c.models.isInstalled(model)) c.ai.setActiveModel(model)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("KI-Modelle") }, navigationIcon = { BackButton { nav.back() } })
        }
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                IndexCard(
                    model = active?.takeIf { it.id in installed },
                    indexed = indexed,
                    total = indexable,
                    progress = progress,
                    paused = settings.indexPaused,
                    waitingText = progress?.let { p -> BackgroundJobs.waitingReason(context, p.state, settings.indexOnlyWhileCharging, p.attempts) },
                    onStart = { scope.launch { c.ai.resumeIndexing() } },
                    onPause = { scope.launch { c.ai.pauseIndexing() } },
                    onReset = { active?.let { m -> scope.launch { c.ai.clearIndex(m); c.ai.resumeIndexing() } } },
                )
            }
            item {
                PcImportCard(
                    status = importStatus,
                    onPick = { indexFileLauncher.launch(arrayOf("*/*")) },
                )
            }
            item {
                Text(
                    "Alle Modelle laufen vollständig offline mit ONNX Runtime auf der CPU des Tensor G5. " +
                        "Nur der einmalige Download braucht Internet. Größere Modelle nutzen mehr der 16 GB RAM und " +
                        "brauchen länger zum Indexieren, finden dafür aber auch Details, Text und abstrakte Begriffe.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            item {
                Text("Bildinhalte (Suche)", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
            }
            items(ModelCatalog.models, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    isActive = active == model,
                    isInstalled = model.id in installed,
                    download = downloads[model.id],
                    error = failed[model.id],
                    galleryCount = indexable,
                    onDownload = { c.ai.download(model) },
                    onCancel = { c.ai.cancelDownload(model) },
                    onActivate = { scope.launch { c.ai.setActiveModel(model) } },
                    onDelete = { confirmDelete = model },
                    onImport = {
                        importTarget = model
                        importLauncher.launch(arrayOf("*/*"))
                    },
                )
            }
            item {
                Text("Gesichtserkennung (Personen)", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp))
                app.lumen.photos.ui.screens.people.FaceModelsSection()
            }
            item {
                Text("KI-Filtervorschläge (Sprachmodell)", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 4.dp))
                LlmModelsSection()
            }
        }
    }

    importStatus?.takeIf { !it.running }?.let { status ->
        ImportResultDialog(status, installed = installed, active = active?.id, onActivate = { id ->
            ModelCatalog.byId(id)?.let { m -> scope.launch { c.ai.setActiveModel(m) } }
        }, onDismiss = { c.ai.dismissImportResult(status.id) })
    }

    confirmDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("${model.name} löschen?") },
            text = { Text("Das Modell (${Format.bytes(c.models.diskUsage(model))}) und sein Suchindex werden entfernt.") },
            confirmButton = {
                Button(onClick = {
                    confirmDelete = null
                    scope.launch { c.ai.deleteModel(model) }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Abbrechen") } }
        )
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

@Composable
private fun IndexCard(
    model: AiModel?,
    indexed: Int,
    total: Int,
    progress: IndexProgress?,
    paused: Boolean,
    waitingText: String?,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(
                    Icons.Outlined.AutoAwesome,
                    shape = MaterialShapes.SoftBurst.toShape(),
                    container = MaterialTheme.colorScheme.tertiary,
                    content = MaterialTheme.colorScheme.onTertiary,
                    size = 56.dp,
                    spin = progress?.running == true
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Suchindex", style = MaterialTheme.typography.titleLarge)
                    Text(model?.let { "${it.tier} · ${it.name}" } ?: "Kein Modell aktiv", style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (model != null) {
                Spacer(Modifier.height(16.dp))
                val fraction = if (total > 0) (indexed.toFloat() / total).coerceIn(0f, 1f) else 0f
                Text("${Format.count(indexed)} von ${Format.count(total)} Elementen indexiert", style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(8.dp))
                LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                progress?.let { p ->
                    if (p.running && p.total > 0) {
                        val eta = (p.total - p.done).toLong() * p.msPerImage / 1000
                        Text(
                            "Läuft: ${p.done}/${p.total} · ${p.msPerImage} ms pro Foto · noch ca. ${Format.etaSeconds(eta)}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    } else if (p.running) {
                        Text("Wird vorbereitet …", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    } else if (waitingText != null) {
                        Text(waitingText, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp))
                    }
                }
                if (paused) {
                    Text(
                        "Pausiert – bereits indexierte Fotos bleiben erhalten, es geht dort weiter, wo du aufgehört hast.",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (progress != null && !paused) {
                        FilledTonalButton(onClick = onPause) { Icon(Icons.Outlined.Pause, null); Text(" Pausieren") }
                    } else {
                        Button(onClick = onStart, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.PlayArrow, null); Text(if (paused) " Fortsetzen" else " Indexieren")
                        }
                    }
                    OutlinedButton(onClick = onReset) { Icon(Icons.Outlined.Refresh, null); Text(" Neu aufbauen") }
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: AiModel,
    isActive: Boolean,
    isInstalled: Boolean,
    download: Float?,
    error: String?,
    galleryCount: Int,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
    onImport: () -> Unit,
) {
    val border by animateColorAsState(if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, label = "b")
    val shape = when (model.tier) {
        "Blitz" -> MaterialShapes.Burst
        "Schnell" -> MaterialShapes.Pill
        "Ausgewogen" -> MaterialShapes.Cookie6Sided
        "Präzise" -> MaterialShapes.Gem
        "Ultra" -> MaterialShapes.Cookie9Sided
        else -> MaterialShapes.Sunny
    }.toShape()
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f) else MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(if (isActive) 2.dp else 1.dp, border),
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(if (model.speed >= 4) Icons.Outlined.Bolt else Icons.Outlined.Star, shape = shape, size = 52.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(model.tier, style = MaterialTheme.typography.headlineSmall)
                    Text(model.name, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isActive) {
                    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.primary) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Check, null, tint = MaterialTheme.colorScheme.onPrimary)
                            Text(" Aktiv", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(model.description, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tempo", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
                Dots(model.speed)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Genauigkeit", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(76.dp))
                Dots(model.accuracy, color = MaterialTheme.colorScheme.tertiary)
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Fact(Icons.Outlined.CloudDownload, Format.bytes(model.totalBytes))
                Fact(Icons.Outlined.Memory, String.format(java.util.Locale.GERMANY, "~%.1f GB RAM", model.ramMb / 1000.0))
                Fact(Icons.Outlined.Speed, "~${Format.etaSeconds(galleryCount.toLong() * model.msPerImage / 1000)} für ${Format.count(galleryCount)}")
                Fact(Icons.Outlined.Language, if (model.multilingual) "Deutsch + 100 Sprachen" else "Nur Englisch")
            }
            if (error != null && download == null && !isInstalled) {
                Text("Download fehlgeschlagen: $error", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }
            AnimatedVisibility(download != null) {
                Column(Modifier.padding(top = 14.dp)) {
                    LinearWavyProgressIndicator(progress = { download ?: 0f }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${Format.percent(download ?: 0f)} von ${Format.bytes(model.totalBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    download != null -> OutlinedButton(onClick = onCancel) { Text("Abbrechen") }
                    !isInstalled -> {
                        Button(onClick = onDownload, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.CloudDownload, null); Text(" Herunterladen")
                        }
                        TextButton(onClick = onImport) { Icon(Icons.Outlined.FileOpen, null); Text(" Dateien importieren") }
                    }
                    isActive -> Unit
                    else -> Button(onClick = onActivate, shapes = ButtonDefaults.shapes()) { Text("Verwenden") }
                }
                Spacer(Modifier.weight(1f))
                if (isInstalled) {
                    IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Löschen") }
                }
            }
        }
    }
}

@Composable
private fun Fact(icon: ImageVector, text: String) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, modifier = Modifier.padding(end = 4.dp).height(16.dp))
            Text(text, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun PcImportCard(status: ImportStatus?, onPick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(32.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth().animateContentSize()
    ) {
        Column(Modifier.padding(22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ShapeIcon(
                    Icons.Outlined.Computer,
                    shape = MaterialShapes.Cookie6Sided.toShape(),
                    container = MaterialTheme.colorScheme.secondary,
                    content = MaterialTheme.colorScheme.onSecondary,
                    size = 56.dp,
                )
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text("Indexierung vom PC", style = MaterialTheme.typography.titleLarge)
                    Text("Viel schneller als auf dem Handy", style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(12.dp))
            val running = status?.takeIf { it.running }
            if (running != null) {
                val fraction = if (running.total > 0) (running.done.toFloat() / running.total).coerceIn(0f, 1f) else 0f
                LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (running.stage == IndexImportWorker.STAGE_MATCH) "Fotos werden zugeordnet …"
                    else "${Format.count(running.done)} von ${Format.count(running.total)} Fotos aus der Datei gelesen",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 8.dp)
                )
            } else {
                Text(
                    "Kopiere die Fotos auf einen Ubuntu-PC, lass sie dort mit der Desktop-App „Lumen Indexer“ " +
                        "analysieren und wähle hier die exportierte .lumenindex-Datei. Die Fotos auf dem Handy " +
                        "werden über Dateiname und Größe zugeordnet – nichts muss neu berechnet werden.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = onPick, shapes = ButtonDefaults.shapes()) {
                    Icon(Icons.Outlined.FileOpen, null); Text(" Datei importieren")
                }
            }
        }
    }
}

@Composable
private fun ImportResultDialog(status: ImportStatus, installed: Set<String>, active: String?, onActivate: (String) -> Unit, onDismiss: () -> Unit) {
    val result: ImportResult? = status.result
    if (result == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Import nicht möglich") },
            text = { Text(status.error ?: "Import fehlgeschlagen.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
        return
    }
    val model = ModelCatalog.byId(result.modelId)
    val modelReady = model != null && model.id in installed
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (result.imported > 0) "Indexierung importiert" else "Nichts zu importieren") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "${Format.count(result.imported)} von ${Format.count(result.onPhone)} Fotos auf dem Handy haben jetzt " +
                        "einen Suchindex (${model?.name ?: result.modelId}). Die Datei enthielt ${Format.count(result.inFile)} Fotos."
                )
                if (result.alreadyIndexed > 0) Text("${Format.count(result.alreadyIndexed)} waren schon indexiert und blieben unverändert.")
                if (result.byName > 0) Text("${Format.count(result.byName)} Fotos wurden nur über den Dateinamen zugeordnet (Größe war anders, z. B. nach dem Komprimieren).")
                if (result.unmatched > 0) Text("${Format.count(result.unmatched)} Fotos auf dem Handy waren nicht in der Datei – sie werden wie gewohnt auf dem Handy indexiert.")
                if (result.imported == 0 && result.alreadyIndexed == 0) {
                    Text("Es passte kein Foto: Dateiname und Größe müssen mit denen auf dem Handy übereinstimmen. Wurde der richtige Ordner indexiert?")
                }
                if (!modelReady && model != null) {
                    Text("Zum Suchen fehlt noch das Modell ${model.name} – lade es weiter unten herunter. Der Index bleibt erhalten.")
                } else if (model != null && active != model.id) {
                    Text("${model.name} ist noch nicht das aktive Modell. Wechsle dazu, um mit diesem Index zu suchen.")
                }
            }
        },
        confirmButton = {
            if (model != null && modelReady && active != model.id) {
                Button(onClick = { onActivate(model.id); onDismiss() }) { Text("${model.tier} verwenden") }
            } else {
                TextButton(onClick = onDismiss) { Text("OK") }
            }
        },
        dismissButton = if (model != null && modelReady && active != model.id) {
            { TextButton(onClick = onDismiss) { Text("Später") } }
        } else null
    )
}
