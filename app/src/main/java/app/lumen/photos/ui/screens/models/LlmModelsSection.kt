package app.lumen.photos.ui.screens.models

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.container
import app.lumen.photos.llm.LlmCatalog
import app.lumen.photos.ui.components.Dots
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.ShapeIcon
import kotlinx.coroutines.launch

/** Optional language model that suggests search filters ("Sommer 2026" → 1.6.–31.8.2026). */
@Composable
fun LlmModelsSection(modifier: Modifier = Modifier) {
    val c = LocalContext.current.container
    val scope = rememberCoroutineScope()
    val active by c.llm.activeModel.collectAsStateWithLifecycle()
    val installed by c.models.installed.collectAsStateWithLifecycle()
    val downloads by c.ai.downloads.collectAsStateWithLifecycle(initialValue = emptyMap())
    val failed by c.ai.failedDownloads.collectAsStateWithLifecycle(initialValue = emptyMap())

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            "Ein kleines Sprachmodell liest deine Suche und schlägt passende Filter vor – z. B. „Annas Hochzeit in München " +
                "vor zwei Jahren“ → Person Anna, Ort München, Jahr. Die Filter selbst bleiben feste Regeln, du entscheidest per Tipp. " +
                "Auch ohne Modell erkennt Lumen Datum, Personen, Orte und Typen schon mit festen Regeln.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        LlmCatalog.models.forEach { model ->
            val isActive = active == model
            val isInstalled = model.id in installed
            val progress = downloads[model.id]
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = if (isActive) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceContainerLow,
                border = BorderStroke(if (isActive) 2.dp else 1.dp, if (isActive) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.fillMaxWidth().animateContentSize(),
            ) {
                Column(Modifier.padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShapeIcon(
                            Icons.Outlined.AutoAwesome,
                            shape = (if (model.speed >= 4) MaterialShapes.Pill else MaterialShapes.Cookie6Sided).toShape(),
                            container = MaterialTheme.colorScheme.secondaryContainer,
                            content = MaterialTheme.colorScheme.onSecondaryContainer,
                            size = 48.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(model.tier, style = MaterialTheme.typography.titleLarge)
                            Text(model.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isActive) Icon(Icons.Outlined.Check, "Aktiv", tint = MaterialTheme.colorScheme.secondary)
                    }
                    Text(model.description, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 8.dp))
                    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Tempo", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
                        Dots(model.speed)
                    }
                    Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Genauigkeit", style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(80.dp))
                        Dots(model.accuracy, color = MaterialTheme.colorScheme.secondary)
                    }
                    Text(
                        "${Format.bytes(model.totalBytes)} · ca. ${Format.bytes(model.ramMb * 1024L * 1024)} RAM während der Suche",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    failed[model.id]?.takeIf { progress == null && !isInstalled }?.let {
                        Text("Download fehlgeschlagen: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    AnimatedVisibility(progress != null) {
                        LinearWavyProgressIndicator(progress = { progress ?: 0f }, modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
                    }
                    Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        when {
                            progress != null -> OutlinedButton(onClick = { c.ai.cancelDownload(model) }) { Text("Abbrechen") }
                            !isInstalled -> Button(onClick = { c.ai.download(model) }, shapes = ButtonDefaults.shapes()) {
                                Icon(Icons.Outlined.CloudDownload, null); Text(" Herunterladen")
                            }
                            !isActive -> Button(onClick = { scope.launch { c.llm.setActive(model) } }, shapes = ButtonDefaults.shapes()) { Text("Verwenden") }
                            else -> OutlinedButton(onClick = { scope.launch { c.llm.setActive(null) } }) { Text("Ausschalten") }
                        }
                        Spacer(Modifier.weight(1f))
                        if (isInstalled) IconButton(onClick = {
                            scope.launch {
                                if (isActive) c.llm.setActive(null) else c.llm.release()
                                c.models.delete(model)
                            }
                        }) { Icon(Icons.Outlined.Delete, "Löschen") }
                    }
                }
            }
        }
    }
}
