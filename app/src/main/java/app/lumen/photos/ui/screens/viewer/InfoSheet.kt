package app.lumen.photos.ui.screens.viewer

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Face
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.container
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.exifinterface.media.ExifInterface
import app.lumen.photos.data.media.MediaItem
import app.lumen.photos.ui.components.Format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

private data class ExifInfo(
    val camera: String?,
    val lens: String?,
    val aperture: String?,
    val exposure: String?,
    val iso: String?,
    val focal: String?,
    val latLong: DoubleArray?,
)

@Composable
fun InfoSheet(item: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var exif by remember { mutableStateOf<ExifInfo?>(null) }
    LaunchedEffect(item.id) {
        exif = withContext(Dispatchers.IO) {
            runCatching {
                val uri = runCatching { MediaStore.setRequireOriginal(item.uri) }.getOrDefault(item.uri)
                (runCatching { context.contentResolver.openInputStream(uri) }.getOrNull()
                    ?: context.contentResolver.openInputStream(item.uri))?.use { input ->
                    val e = ExifInterface(input)
                    val exposure = e.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0).takeIf { it > 0 }?.let {
                        if (it < 1) "1/${(1 / it).toInt()} s" else String.format(Locale.GERMANY, "%.1f s", it)
                    }
                    ExifInfo(
                        camera = listOfNotNull(e.getAttribute(ExifInterface.TAG_MAKE), e.getAttribute(ExifInterface.TAG_MODEL))
                            .joinToString(" ").ifBlank { null },
                        lens = e.getAttribute(ExifInterface.TAG_LENS_MODEL),
                        aperture = e.getAttributeDouble(ExifInterface.TAG_F_NUMBER, 0.0).takeIf { it > 0 }?.let { "ƒ/${String.format(Locale.GERMANY, "%.2f", it)}" },
                        exposure = exposure,
                        iso = e.getAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.let { "ISO $it" },
                        focal = e.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, 0.0).takeIf { it > 0 }?.let { String.format(Locale.GERMANY, "%.1f mm", it) },
                        latLong = e.latLong,
                    )
                }
            }.getOrNull()
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text("Details", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(16.dp))
            PeopleInPhoto(item)
            InfoRow(Icons.Outlined.CalendarMonth, Format.full(item.timestamp), null)
            InfoRow(
                Icons.Outlined.Image,
                item.name,
                buildString {
                    if (item.width > 0) append("${item.displayWidth} × ${item.displayHeight} · ")
                    if (item.isImage && item.width > 0) append(String.format(Locale.GERMANY, "%.1f MP · ", item.megapixels))
                    append(Format.bytes(item.size))
                    if (item.isVideo) append(" · ${Format.duration(item.durationMs)}")
                    append("\n${item.relativePath}")
                }
            )
            exif?.let { e ->
                if (e.camera != null) {
                    InfoRow(Icons.Outlined.CameraAlt, e.camera, e.lens)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(start = 40.dp, bottom = 8.dp)
                    ) {
                        listOfNotNull(e.aperture, e.exposure, e.iso, e.focal).forEach {
                            AssistChip(onClick = {}, label = { Text(it) })
                        }
                    }
                }
                e.latLong?.let { (lat, lon) ->
                    val c = context.container
                    val place = remember(lat, lon) {
                        c.locations.directory.takeIf { it.loaded }?.nearest(lat, lon)?.let { city ->
                            listOfNotNull(city.name, c.locations.directory.regionName(city.region), c.locations.directory.countryName(city.country))
                                .distinct().joinToString(", ")
                        }
                    }
                    InfoRow(Icons.Outlined.Place, place ?: String.format(Locale.US, "%.5f, %.5f", lat, lon), if (place != null) String.format(Locale.US, "%.5f, %.5f", lat, lon) else "Aufnahmeort")
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("geo:$lat,$lon?q=$lat,$lon")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                        modifier = Modifier.padding(start = 32.dp)
                    ) { Text("In Karten-App öffnen") }
                }
            }
        }
    }
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, subtitle: String?) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.Top) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PeopleInPhoto(item: MediaItem) {
    val c = LocalContext.current.container
    val nav = app.lumen.photos.ui.navigation.LocalNavigator.current
    val persons by c.faces.persons.collectAsStateWithLifecycle()
    val faces by androidx.compose.runtime.produceState(emptyList<app.lumen.photos.data.db.FaceEntity>(), item.id) {
        val model = c.faces.activeModel.value
        value = if (model == null) emptyList() else c.db.faces().facesOfMedia(model.id, listOf(item.id))
    }
    if (faces.isEmpty()) return
    val shown = faces.mapNotNull { f -> persons.firstOrNull { it.id == f.personId }?.let { f to it } }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Face, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (shown.isEmpty()) Text("${faces.size} Gesicht(er) erkannt", style = MaterialTheme.typography.bodyLarge)
            shown.forEach { (face, person) ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.width(64.dp).clickable { nav.person(person.id) }
                ) {
                    app.lumen.photos.ui.components.FaceImage(
                        app.lumen.photos.ui.components.FaceCrop(item.uri, face.id, face.left, face.top, face.right, face.bottom, 160),
                        androidx.compose.foundation.shape.CircleShape,
                        Modifier.size(52.dp)
                    )
                    Text(person.name ?: "Unbekannt", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}
