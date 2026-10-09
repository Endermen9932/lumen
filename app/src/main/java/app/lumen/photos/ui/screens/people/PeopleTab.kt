package app.lumen.photos.ui.screens.people

import androidx.compose.animation.AnimatedVisibility
import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.automirrored.outlined.CallMerge
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.FilledTonalButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Face
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.TextButton
import app.lumen.photos.work.BackgroundJobs
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import app.lumen.photos.container
import app.lumen.photos.face.Person
import app.lumen.photos.ui.components.FaceImage
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.ShapeIcon
import app.lumen.photos.ui.components.crop
import app.lumen.photos.ui.navigation.LocalNavigator
import kotlinx.coroutines.launch

/** Expressive shapes the person portraits cycle through. */
val PortraitShapes = listOf(
    MaterialShapes.Cookie9Sided, MaterialShapes.Circle, MaterialShapes.Clover4Leaf, MaterialShapes.Cookie6Sided,
    MaterialShapes.Sunny, MaterialShapes.SoftBurst, MaterialShapes.Cookie12Sided, MaterialShapes.Pentagon,
)

@Composable
fun PeopleTab(onSelectionModeChange: (Boolean) -> Unit = {}) {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val model by c.faces.activeModel.collectAsStateWithLifecycle()
    val installed by c.models.installed.collectAsStateWithLifecycle()
    val persons by c.faces.persons.collectAsStateWithLifecycle()
    val progress by c.faces.progress.collectAsStateWithLifecycle(initialValue = null)
    val scanned by c.faces.scannedCount.collectAsStateWithLifecycle()
    val settings by c.settings.settings.collectAsStateWithLifecycle()
    val media by c.media.media.collectAsStateWithLifecycle()
    val photoCount = remember(media) { media.count { it.isImage } }
    val paused = settings.facesPaused
    val context = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    var showHidden by remember { mutableStateOf(false) }
    var modelSheet by remember { mutableStateOf(false) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var mergeSelection by remember { mutableStateOf(false) }
    val ready = model != null && model!!.id in installed
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    if (!ready) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(top = top + 16.dp, bottom = bottom + 110.dp, start = 16.dp, end = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ShapeIcon(Icons.Outlined.Face, size = 112.dp, shape = MaterialShapes.Cookie12Sided.toShape(), spin = true)
            Spacer(Modifier.height(20.dp))
            Text("Personen erkennen", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                "Lumen findet Gesichter in deinen Fotos und gruppiert sie – komplett offline auf dem Gerät. " +
                    "Gib Personen Namen und suche dann einfach nach „Paul“.",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            FaceModelsSection(Modifier.fillMaxWidth())
        }
        return
    }

    val visible = persons.filter { showHidden || !it.hidden }
    val named = visible.filter { it.name != null }
    val unnamed = visible.filter { it.name == null }
    // Persons that disappeared (hidden, merged, regrouped) drop out of the selection by themselves.
    val selected = remember(selection, persons) { persons.filter { it.id in selection } }
    val selecting = selected.isNotEmpty()
    LaunchedEffect(selecting) { onSelectionModeChange(selecting) }
    BackHandler(enabled = selecting) { selection = emptySet() }
    fun toggle(p: Person) { selection = if (p.id in selection) selection - p.id else selection + p.id }

    Box(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top + 12.dp, bottom = bottom + 110.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Personen", style = MaterialTheme.typography.displaySmall)
                        Text(
                            "${model!!.tier} · ${Format.count(scanned)} von ${Format.count(photoCount)} Fotos gescannt",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Mehr") }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Jetzt scannen") }, onClick = { menu = false; scope.launch { c.faces.resume() } })
                            if (progress != null && !paused) {
                                DropdownMenuItem(text = { Text("Scan pausieren") }, onClick = { menu = false; scope.launch { c.faces.pause() } })
                            }
                            DropdownMenuItem(text = { Text("Neu gruppieren") }, onClick = { menu = false; scope.launch { c.faces.regroup() } })
                            DropdownMenuItem(
                                text = { Text(if (showHidden) "Ausgeblendete verbergen" else "Ausgeblendete zeigen") },
                                onClick = { menu = false; showHidden = !showHidden }
                            )
                            DropdownMenuItem(text = { Text("Erkennungsqualität …") }, onClick = { menu = false; modelSheet = true })
                        }
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                AnimatedVisibility(progress != null || (paused && scanned < photoCount)) {
                    val p = progress
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    paused -> "Gesichtserkennung pausiert – es geht dort weiter, wo sie aufgehört hat"
                                    p != null && p.total > 0 -> "Gesichter suchen: ${Format.count(p.done)} von ${Format.count(p.total)}" +
                                        (if (p.msPerImage > 0) " · noch ca. ${Format.etaSeconds((p.total - p.done).toLong() * p.msPerImage / 1000)}" else "")
                                    p != null && p.running -> "Gesichtserkennung wird vorbereitet …"
                                    p != null -> BackgroundJobs.waitingReason(context, p.state, settings.indexOnlyWhileCharging, p.attempts)
                                    else -> ""
                                },
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f)
                            )
                            if (paused) {
                                TextButton(onClick = { scope.launch { c.faces.resume() } }) {
                                    Icon(Icons.Outlined.PlayArrow, null); Text(" Fortsetzen")
                                }
                            } else {
                                TextButton(onClick = { scope.launch { c.faces.pause() } }) {
                                    Icon(Icons.Outlined.Pause, null); Text(" Pausieren")
                                }
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        val fraction = if (photoCount > 0) (scanned.toFloat() / photoCount).coerceIn(0f, 1f) else 0f
                        when {
                            paused -> LinearWavyProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), amplitude = { 0f })
                            p != null && p.total > 0 -> LinearWavyProgressIndicator(progress = { p.done.toFloat() / p.total }, modifier = Modifier.fillMaxWidth())
                            else -> LinearWavyProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            if (visible.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (progress != null || paused) "Sobald ein Gesicht auf mindestens 3 Fotos gefunden wurde, erscheint die Person hier – schon während des Scans."
                        else "Noch keine Personen gefunden. Personen erscheinen, sobald ein Gesicht auf mindestens 3 Fotos vorkommt.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
            if (named.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) { Text("Benannt", style = MaterialTheme.typography.titleMedium) }
                items(named, key = { it.id }) { p ->
                    PersonCard(
                        p, Modifier.animateItem(), selected = p.id in selection,
                        onLongClick = { toggle(p) },
                    ) { if (selecting) toggle(p) else nav.person(p.id) }
                }
            }
            if (unnamed.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (named.isEmpty()) "Häufige Gesichter" else "Weitere Personen",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = { selection = unnamed.mapTo(HashSet()) { it.id } }) { Text("Alle auswählen") }
                    }
                }
                items(unnamed, key = { it.id }) { p ->
                    PersonCard(
                        p, Modifier.animateItem(), selected = p.id in selection,
                        onLongClick = { toggle(p) },
                    ) { if (selecting) toggle(p) else nav.person(p.id) }
                }
            }
        }

        AnimatedVisibility(
            visible = selecting,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter)
        ) {
            val allHidden = selected.all { it.hidden }
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                shape = RoundedCornerShape(28.dp),
                shadowElevation = 6.dp,
                modifier = Modifier.statusBarsPadding().padding(horizontal = 10.dp, vertical = 6.dp).fillMaxWidth()
            ) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { selection = emptySet() }) { Icon(Icons.Filled.Close, "Auswahl aufheben") }
                    Text("${selected.size} ausgewählt", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    IconButton(onClick = { selection = visible.filter { it.name == null }.mapTo(HashSet()) { it.id } }) {
                        Icon(Icons.Outlined.SelectAll, "Alle Unbekannten auswählen")
                    }
                    if (selected.size >= 2) {
                        IconButton(onClick = { mergeSelection = true }) {
                            Icon(Icons.AutoMirrored.Outlined.CallMerge, "Zusammenführen")
                        }
                    }
                    FilledTonalButton(onClick = {
                        val ids = selected.map { it.id }
                        val hide = !allHidden
                        selection = emptySet()
                        scope.launch {
                            c.faces.setHidden(ids, hide)
                            Toast.makeText(
                                context,
                                if (hide) "${ids.size} ausgeblendet – über „Ausgeblendete zeigen“ wieder sichtbar" else "${ids.size} wieder eingeblendet",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) {
                        Icon(if (allHidden) Icons.Outlined.Visibility else Icons.Outlined.VisibilityOff, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (allHidden) "Einblenden" else "Ausblenden")
                    }
                }
            }
        }
    }

    if (mergeSelection && selected.size >= 2) {
        // The person to keep comes first: a named one with the most photos.
        val ordered = selected.sortedWith(compareByDescending<Person> { it.name != null }.thenByDescending { it.mediaIds.size })
        MergeDialog(
            persons = ordered,
            title = "${selected.size} Personen zusammenführen",
            hint = "Alle Fotos landen bei einer Person. Welche soll bleiben (mit ihrem Namen und Bild)?",
            onDismiss = { mergeSelection = false },
            onPick = { keep ->
                mergeSelection = false
                val others = selected.map { it.id }.filter { it != keep.id }
                selection = emptySet()
                scope.launch {
                    c.faces.mergeAll(keep.id, others)
                    Toast.makeText(context, "${others.size + 1} Personen zu „${keep.displayName}“ zusammengeführt", Toast.LENGTH_SHORT).show()
                }
            },
        )
    }

    if (modelSheet) {
        ModalBottomSheet(onDismissRequest = { modelSheet = false }) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                Text("Erkennungsqualität", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 12.dp))
                FaceModelsSection()
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PersonCard(
    person: Person,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val c = LocalContext.current.container
    val cover by produceState<app.lumen.photos.data.db.FaceEntity?>(null, person.coverFace) {
        value = person.coverFace?.let { c.faces.face(it) }
    }
    val item = remember(cover) { cover?.let { f -> c.media.byId(f.mediaId) } }
    val shape = PortraitShapes[(person.id % PortraitShapes.size).toInt()].toShape()
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).combinedClickable(onLongClick = onLongClick, onClick = onClick)
        ) {
            FaceImage(if (cover != null && item != null) cover!!.crop(item.uri) else null, shape, Modifier.fillMaxSize())
            if (selected) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)))
                Icon(
                    Icons.Filled.CheckCircle, "Ausgewählt",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.align(Alignment.Center).size(36.dp)
                )
            }
        }
        Text(
            person.name ?: "Namen hinzufügen",
            style = MaterialTheme.typography.titleSmall,
            color = if (person.name == null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp)
        )
        Text(
            "${Format.count(person.mediaIds.size)} Fotos" + if (person.hidden) " · ausgeblendet" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

