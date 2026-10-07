package app.lumen.photos.ui.screens.people

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.container
import app.lumen.photos.data.db.FaceEntity
import app.lumen.photos.face.Person
import app.lumen.photos.ui.components.BackButton
import app.lumen.photos.ui.components.FaceImage
import app.lumen.photos.ui.components.Format
import app.lumen.photos.ui.components.MediaGrid
import app.lumen.photos.ui.components.SelectionBar
import app.lumen.photos.ui.components.crop
import app.lumen.photos.ui.navigation.LocalNavigator
import kotlinx.coroutines.launch

@Composable
fun PersonScreen(personId: Long) {
    val c = LocalContext.current.container
    val nav = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val persons by c.faces.persons.collectAsStateWithLifecycle()
    val person = persons.firstOrNull { it.id == personId }
    val entity by c.faces.personFlow(personId).collectAsStateWithLifecycle(initialValue = null)
    val media by c.media.media.collectAsStateWithLifecycle()
    val items = remember(person, media) {
        val ids = person?.mediaIds?.toSet().orEmpty()
        media.filter { it.id in ids }
    }
    val source = remember(items) { c.lists.register("person:$personId", items) }
    var selection by remember { mutableStateOf(emptySet<Long>()) }
    var columns by remember { mutableIntStateOf(3) }
    var rename by remember { mutableStateOf(false) }
    var mergeDialog by remember { mutableStateOf(false) }
    var coverPicker by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    val cover by produceState<FaceEntity?>(null, entity?.coverFaceId) { value = entity?.coverFaceId?.let { c.faces.face(it) } }
    val coverItem = remember(cover, media) { cover?.let { f -> media.firstOrNull { it.id == f.mediaId } } }
    BackHandler(selection.isNotEmpty()) { selection = emptySet() }

    Scaffold(
        topBar = {
            if (selection.isNotEmpty()) {
                SelectionBar(
                    selected = items.filter { it.id in selection },
                    onClear = { selection = emptySet() },
                    onSelectAll = { selection = items.mapTo(HashSet()) { it.id } },
                    modifier = Modifier.statusBarsPadding().padding(vertical = 6.dp)
                )
            } else {
                TopAppBar(
                    title = { Text(entity?.name ?: "Unbekannte Person", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = { BackButton { nav.back() } },
                    actions = {
                        IconButton(onClick = { rename = true }) { Icon(Icons.Outlined.Edit, "Namen ändern") }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "Mehr") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Bild ändern") }, onClick = { menu = false; coverPicker = true })
                                DropdownMenuItem(text = { Text("Mit anderer Person zusammenführen") }, onClick = { menu = false; mergeDialog = true })
                                DropdownMenuItem(
                                    text = { Text(if (entity?.hidden == true) "Wieder anzeigen" else "Ausblenden") },
                                    onClick = { menu = false; scope.launch { c.faces.setHidden(personId, entity?.hidden != true) } }
                                )
                            }
                        }
                    }
                )
            }
        }
    ) { padding ->
        MediaGrid(
            items = items,
            columns = columns,
            onColumnsChange = { columns = it },
            selection = selection,
            onSelectionChange = { selection = it },
            onOpen = { nav.viewer(source, it.id) },
            contentPadding = PaddingValues(top = padding.calculateTopPadding(), bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp),
            header = {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    val shape = PortraitShapes[(personId % PortraitShapes.size).toInt()].toShape()
                    FaceImage(
                        if (cover != null && coverItem != null) cover!!.crop(coverItem.uri, 480) else null,
                        shape,
                        Modifier.size(150.dp).clickable { coverPicker = true }
                    )
                    TextButton(onClick = { coverPicker = true }) { Text("Bild ändern") }
                    Text(
                        entity?.name ?: "Wer ist das?",
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.clickable { rename = true }
                    )
                    Text("${Format.count(items.size)} Fotos", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (entity?.name == null) {
                            FilledTonalButton(onClick = { rename = true }) { Text("Namen geben") }
                        }
                        Button(onClick = { nav.review(personId) }, shapes = ButtonDefaults.shapes()) {
                            Icon(Icons.Outlined.Style, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Fotos prüfen")
                        }
                    }
                    Text(
                        "Wische durch bis zu 50 Fotos, bei denen die KI unsicher ist: rechts = ja, links = nein.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            },
        )
    }

    if (coverPicker) {
        CoverPickerSheet(
            personId = personId,
            currentFaceId = entity?.coverFaceId,
            onPick = { face ->
                coverPicker = false
                scope.launch { c.faces.setCover(personId, face.id) }
            },
            onDismiss = { coverPicker = false }
        )
    }

    if (rename) {
        var text by remember { mutableStateOf(entity?.name ?: "") }
        val suggestions = persons.mapNotNull { it.name }.filter { text.isNotBlank() && it.contains(text, true) && it != entity?.name }.distinct().take(4)
        AlertDialog(
            onDismissRequest = { rename = false },
            title = { Text("Wie heißt diese Person?") },
            text = {
                Column {
                    OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                    suggestions.forEach { s ->
                        Text(
                            "„$s“ – mit bestehender Person zusammenführen",
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { text = s }.padding(vertical = 10.dp, horizontal = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    rename = false
                    scope.launch { c.faces.rename(personId, text) }
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { rename = false }) { Text("Abbrechen") } }
        )
    }

    if (mergeDialog) {
        MergeDialog(
            persons = persons.filter { it.id != personId },
            onDismiss = { mergeDialog = false },
            onPick = { other ->
                mergeDialog = false
                scope.launch {
                    c.faces.merge(keep = other.id, remove = personId)
                    nav.back()
                    nav.person(other.id)
                }
            }
        )
    }
}

@Composable
private fun MergeDialog(persons: List<Person>, onDismiss: () -> Unit, onPick: (Person) -> Unit) {
    val c = LocalContext.current.container
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zusammenführen mit …") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(persons, key = { it.id }) { p ->
                    val cover by produceState<FaceEntity?>(null, p.coverFace) { value = p.coverFace?.let { c.faces.face(it) } }
                    val item = remember(cover) { cover?.let { c.media.byId(it.mediaId) } }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(p) }.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val shape = PortraitShapes[(p.id % PortraitShapes.size).toInt()].toShape()
                        FaceImage(if (cover != null && item != null) cover!!.crop(item.uri, 160) else null, shape, Modifier.size(48.dp))
                        Column(Modifier.padding(start = 12.dp)) {
                            Text(p.displayName)
                            Text("${p.mediaIds.size} Fotos", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } }
    )
}
