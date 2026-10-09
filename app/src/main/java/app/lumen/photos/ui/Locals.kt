package app.lumen.photos.ui

import android.app.Activity
import android.content.IntentSender
import androidx.activity.ComponentActivity
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import app.lumen.photos.data.settings.AppSettings
import kotlinx.coroutines.CompletableDeferred

val LocalSharedTransitionScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalNavAnimatedScope = compositionLocalOf<AnimatedVisibilityScope?> { null }
val LocalIntentSenderLauncher = staticCompositionLocalOf<IntentSenderLauncher> { error("No launcher") }
val LocalAppSettings = compositionLocalOf { AppSettings() }

/** Launches MediaStore consent dialogs (trash, favourite, write …) and suspends until they finish. */
class IntentSenderLauncher(activity: ComponentActivity) {
    private var pending: CompletableDeferred<Boolean>? = null
    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        pending?.complete(result.resultCode == Activity.RESULT_OK)
        pending = null
    }

    suspend fun launch(sender: IntentSender): Boolean {
        pending?.complete(false)
        val deferred = CompletableDeferred<Boolean>()
        pending = deferred
        launcher.launch(IntentSenderRequest.Builder(sender).build())
        return deferred.await()
    }
}

private val MediaBoundsTransform = BoundsTransform { _: Rect, _: Rect ->
    spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow)
}

/** Shared element transition between a thumbnail and the full screen viewer. */
@Composable
fun Modifier.sharedMedia(key: Any, enabled: Boolean = true): Modifier {
    val shared = LocalSharedTransitionScope.current ?: return this
    val visibility = LocalNavAnimatedScope.current ?: return this
    // Navigation animations switched off: no flight between grid and viewer either.
    if (!enabled || !LocalAppSettings.current.backGestureAnimations) return this
    return with(shared) {
        this@sharedMedia.sharedElement(
            sharedContentState = rememberSharedContentState("media-$key"),
            animatedVisibilityScope = visibility,
            boundsTransform = MediaBoundsTransform,
        )
    }
}
