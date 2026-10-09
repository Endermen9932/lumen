package app.lumen.photos.ui.screens.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import app.lumen.photos.ui.LocalAppSettings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.outlined.Face
import app.lumen.photos.ui.screens.people.PeopleTab
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Photo
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import app.lumen.photos.ui.screens.albums.AlbumsTab
import app.lumen.photos.ui.screens.search.SearchTab
import app.lumen.photos.ui.screens.timeline.TimelineTab
import app.lumen.photos.ui.screens.tools.ToolsTab

private data class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab("Fotos", Icons.Outlined.Photo, Icons.Filled.Photo),
    Tab("Alben", Icons.Outlined.PhotoLibrary, Icons.Filled.PhotoLibrary),
    Tab("Suche", Icons.Outlined.Search, Icons.Filled.Search),
    Tab("Personen", Icons.Outlined.Face, Icons.Filled.Face),
    Tab("Werkzeuge", Icons.Outlined.Build, Icons.Filled.Build),
)

@Composable
fun HomeScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var hideBar by remember { mutableStateOf(false) }
    val holder = rememberSaveableStateHolder()
    val timelineState = rememberLazyGridState()

    BackHandler(enabled = tab != 0) { tab = 0 }
    val animations = LocalAppSettings.current.backGestureAnimations

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                if (animations) {
                    (fadeIn(tween(220, delayMillis = 60)) + scaleIn(initialScale = 0.96f, animationSpec = tween(280)))
                        .togetherWith(fadeOut(tween(90)))
                } else {
                    EnterTransition.None.togetherWith(ExitTransition.None)
                }
            },
            label = "tabs",
        ) { current ->
            holder.SaveableStateProvider(current) {
                when (current) {
                    0 -> TimelineTab(timelineState, onSelectionModeChange = { hideBar = it })
                    1 -> AlbumsTab()
                    2 -> SearchTab(onSelectionModeChange = { hideBar = it })
                    3 -> PeopleTab(onSelectionModeChange = { hideBar = it })
                    else -> ToolsTab()
                }
            }
        }
        AnimatedVisibility(
            visible = !hideBar,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            ShortNavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.96f)) {
                tabs.forEachIndexed { i, t ->
                    ShortNavigationBarItem(
                        selected = tab == i,
                        onClick = {
                            if (tab == i && i == 0) {
                                // Second tap on "Fotos" scrolls to the top.
                                hideBar = false
                            }
                            tab = i
                        },
                        icon = { Icon(if (tab == i) t.selectedIcon else t.icon, contentDescription = t.label) },
                        label = { Text(t.label) },
                    )
                }
            }
        }
    }
}
