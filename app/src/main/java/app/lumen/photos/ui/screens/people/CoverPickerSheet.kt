package app.lumen.photos.ui.screens.people

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.container
import app.lumen.photos.data.db.FaceEntity
import app.lumen.photos.ui.components.FaceImage
import app.lumen.photos.ui.components.crop

/** Lets the user choose which of a person's detected faces is shown as their picture. */
@Composable
fun CoverPickerSheet(personId: Long, currentFaceId: Long?, onPick: (FaceEntity) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current.container
    val media by c.media.media.collectAsStateWithLifecycle()
    val byId = remember(media) { media.associateBy { it.id } }
    val faces by produceState<List<FaceEntity>>(emptyList(), personId) {
        value = c.faces.facesOfPerson(personId)
            .sortedByDescending { (if (it.confirmed) 1f else 0f) + it.score + (it.right - it.left) }
            .take(240)
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text("Bild auswählen", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 16.dp))
        Text(
            "Tippe auf das Gesicht, das als Bild dieser Person angezeigt werden soll.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)
        )
        val shape = RoundedCornerShape(20.dp)
        LazyVerticalGrid(
            columns = GridCells.Adaptive(88.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp)
        ) {
            items(faces, key = { it.id }) { face ->
                val item = byId[face.mediaId]
                FaceImage(
                    if (item != null) face.crop(item.uri, 240) else null,
                    shape,
                    Modifier
                        .aspectRatio(1f)
                        .then(if (face.id == currentFaceId) Modifier.border(3.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
                        .clip(shape)
                        .clickable { onPick(face) }
                )
            }
        }
    }
}
