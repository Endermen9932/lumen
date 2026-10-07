package app.lumen.photos.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.BuildConfig
import app.lumen.photos.container
import app.lumen.photos.data.settings.AppSettings
import app.lumen.photos.data.settings.ThemeMode
import app.lumen.photos.ui.components.BackButton
import app.lumen.photos.ui.components.ConnectedToggleGroup
import app.lumen.photos.ui.components.SectionTitle
import app.lumen.photos.ui.navigation.LocalNavigator
import app.lumen.photos.ui.theme.SeedColors
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val c = context.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val s by c.settings.settings.collectAsStateWithLifecycle()
    var tick by remember { mutableIntStateOf(0) }
    val canManage = remember(tick) { c.media.canManageMedia() }
    val manageLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { tick++ }
    fun update(t: (AppSettings) -> AppSettings) = scope.launch { c.settings.update(t) }
    fun chargingChanged(v: Boolean) = scope.launch {
        c.settings.update { it.copy(indexOnlyWhileCharging = v) }
        // Re-create the jobs with the new condition (paused jobs stay paused).
        c.ai.scheduleIndexing(replace = true)
        c.faces.schedule(replace = true)
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Einstellungen") }, navigationIcon = { BackButton { nav.back() } }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle("Design")
            ConnectedToggleGroup(
                options = ThemeMode.entries,
                selected = s.themeMode,
                onSelect = { m -> update { it.copy(themeMode = m) } },
                label = { when (it) { ThemeMode.SYSTEM -> "System"; ThemeMode.LIGHT -> "Hell"; ThemeMode.DARK -> "Dunkel" } },
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            SwitchRow("Dynamische Farben", "Farben passend zu deinem Hintergrundbild (Material You)", s.dynamicColor) { v -> update { it.copy(dynamicColor = v) } }
            if (!s.dynamicColor) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SeedColors.forEach { color ->
                        val sel = s.seedColor == color
                        Box(
                            Modifier
                                .size(36.dp)
                                .background(Color(color), CircleShape)
                                .then(if (sel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                                .clickable { update { it.copy(seedColor = color) } },
                            contentAlignment = Alignment.Center
                        ) { if (sel) Icon(Icons.Filled.Check, null, tint = Color.White) }
                    }
                }
            }
            SwitchRow("AMOLED-Schwarz", "Reines Schwarz im dunklen Design – spart Akku auf dem OLED-Display", s.amoledBlack) { v -> update { it.copy(amoledBlack = v) } }
            SwitchRow("Reduzierte Bewegung", "Ruhigere Standard-Animationen statt Expressive-Federn", s.reduceMotion) { v -> update { it.copy(reduceMotion = v) } }
            SwitchRow(
                "Animation bei Zurück-Geste",
                "Seiten und Fotos folgen beim Zurückwischen dem Finger. Aus: Seiten schließen sofort ohne Animation",
                s.backGestureAnimations
            ) { v -> update { it.copy(backGestureAnimations = v) } }

            SectionTitle("Galerie")
            ListItem(
                headlineContent = { Text("Spalten in der Fotoübersicht: ${s.gridColumns}") },
                supportingContent = {
                    Column {
                        Text("Tipp: In der Übersicht mit zwei Fingern zoomen")
                        Slider(value = s.gridColumns.toFloat(), onValueChange = { v -> update { it.copy(gridColumns = v.toInt()) } }, valueRange = 2f..8f, steps = 5)
                    }
                }
            )
            SwitchRow("Videos in der Timeline", null, s.showVideosInTimeline) { v -> update { it.copy(showVideosInTimeline = v) } }
            SwitchRow("Erinnerungen anzeigen", "„Vor X Jahren“ oben in der Timeline", s.showMemories) { v -> update { it.copy(showMemories = v) } }

            SectionTitle("KI & Suche")
            ListItem(
                headlineContent = { Text("CPU-Threads: ${s.aiThreads}") },
                supportingContent = {
                    Column {
                        Text("Mehr Threads = schneller, aber wärmer. Der Tensor G5 hat 8 Kerne.")
                        Slider(value = s.aiThreads.toFloat(), onValueChange = { v -> update { it.copy(aiThreads = v.toInt()) } }, valueRange = 1f..8f, steps = 6)
                    }
                }
            )
            var strict by remember(s.searchStrictness) { mutableStateOf(s.searchStrictness) }
            ListItem(
                headlineContent = { Text("Such-Genauigkeit") },
                supportingContent = {
                    Column {
                        Text(if (strict < 1.8f) "Mehr Treffer" else if (strict > 2.8f) "Nur sehr passende Treffer" else "Ausgewogen")
                        Slider(value = strict, onValueChange = { strict = it }, onValueChangeFinished = { update { it.copy(searchStrictness = strict) } }, valueRange = 1.2f..3.6f)
                    }
                }
            )
            SwitchRow("Neue Fotos automatisch indexieren", null, s.autoIndexNewMedia) { v -> update { it.copy(autoIndexNewMedia = v) } }
            SwitchRow(
                "Nur beim Laden indexieren",
                "Aus: Indexierung und Gesichtserkennung laufen sofort, ganz ohne Akku-Bedingungen",
                s.indexOnlyWhileCharging
            ) { v -> chargingChanged(v) }
            SwitchRow(
                "Display während Aufgaben anlassen",
                "Beim Indexieren und Komprimieren wird der Bildschirm gedimmt, geht aber nie aus – so läuft alles mit voller Geschwindigkeit",
                s.keepScreenOnDuringWork
            ) { v -> update { it.copy(keepScreenOnDuringWork = v) } }
            SwitchRow("Videos indexieren", "Anhand eines Vorschaubilds", s.indexVideos) { v -> update { it.copy(indexVideos = v) } }
            SwitchRow("XNNPACK-Beschleunigung", "Experimentell: optimierte ARM-Kernel für ONNX Runtime", s.useXnnpack) { v -> update { it.copy(useXnnpack = v) } }

            SectionTitle("Speicher & Rechte")
            ListItem(
                headlineContent = { Text("Medienverwaltung") },
                supportingContent = { Text(if (canManage) "Erlaubt – Löschen, Favoriten und Optimieren ohne Rückfrage" else "Nicht erlaubt – Android fragt bei jeder Aktion nach") },
                trailingContent = {
                    TextButton(onClick = {
                        manageLauncher.launch(Intent(Settings.ACTION_REQUEST_MANAGE_MEDIA).setData(Uri.parse("package:${context.packageName}")))
                    }) { Text(if (canManage) "Ändern" else "Erlauben") }
                }
            )
            ListItem(
                headlineContent = { Text("App-Berechtigungen") },
                supportingContent = { Text("Fotozugriff, Standortdaten, Benachrichtigungen") },
                trailingContent = {
                    TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                    }) { Text("Öffnen") }
                }
            )

            SectionTitle("Über")
            ListItem(
                headlineContent = { Text("Lumen ${BuildConfig.VERSION_NAME}") },
                supportingContent = {
                    Text("Offline-Galerie ohne Google-Dienste. KI: ONNX Runtime mit MobileCLIP (Apple) und SigLIP 2 (Google, Apache 2.0) · Bildanzeige: Coil & Telephoto · Video: Media3")
                }
            )

            DeveloperSection()
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = onChange) },
        modifier = Modifier.clickable { onChange(!checked) }
    )
}
