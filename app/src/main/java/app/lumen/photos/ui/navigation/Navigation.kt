package app.lumen.photos.ui.navigation

import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateDp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import app.lumen.photos.ui.LocalAppSettings
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import app.lumen.photos.ui.screens.people.PersonScreen
import app.lumen.photos.ui.screens.people.ReviewScreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import app.lumen.photos.ui.LocalNavAnimatedScope
import app.lumen.photos.ui.LocalSharedTransitionScope
import app.lumen.photos.ui.screens.backup.BackupScreen
import app.lumen.photos.ui.screens.collection.CollectionScreen
import app.lumen.photos.ui.screens.duplicates.DuplicatesScreen
import app.lumen.photos.ui.screens.editor.EditorScreen
import app.lumen.photos.ui.screens.home.HomeScreen
import app.lumen.photos.ui.screens.models.ModelsScreen
import app.lumen.photos.ui.screens.onboarding.OnboardingScreen
import app.lumen.photos.ui.screens.optimize.OptimizeScreen
import app.lumen.photos.ui.screens.optimize.VideoOptimizeScreen
import app.lumen.photos.ui.screens.settings.SettingsScreen
import app.lumen.photos.ui.screens.viewer.ExternalViewerScreen
import app.lumen.photos.ui.screens.viewer.ViewerScreen
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object OnboardingRoute
@Serializable data class ViewerRoute(val source: String, val id: Long)
@Serializable data class ExternalViewerRoute(val uri: String, val mime: String?)
@Serializable data class CollectionRoute(val source: String, val title: String)
@Serializable object OptimizeRoute
@Serializable object DuplicatesRoute
@Serializable object ModelsRoute
@Serializable object SettingsRoute
@Serializable data class EditorRoute(val id: Long)
@Serializable object BackupRoute
@Serializable object VideoOptimizeRoute
@Serializable data class PersonRoute(val id: Long)
@Serializable data class ReviewRoute(val personId: Long)

class Navigator(private val controller: NavHostController) {
    fun back() {
        controller.popBackStack()
    }

    fun viewer(source: String, id: Long) = controller.navigate(ViewerRoute(source, id)) { launchSingleTop = true }
    fun collection(source: String, title: String) = controller.navigate(CollectionRoute(source, title))
    fun optimize() = controller.navigate(OptimizeRoute) { launchSingleTop = true }
    fun duplicates() = controller.navigate(DuplicatesRoute) { launchSingleTop = true }
    fun models() = controller.navigate(ModelsRoute) { launchSingleTop = true }
    fun settings() = controller.navigate(SettingsRoute) { launchSingleTop = true }
    fun editor(id: Long) = controller.navigate(EditorRoute(id))
    fun backup() = controller.navigate(BackupRoute) { launchSingleTop = true }
    fun videoOptimize() = controller.navigate(VideoOptimizeRoute) { launchSingleTop = true }
    fun person(id: Long) = controller.navigate(PersonRoute(id))
    fun review(personId: Long) = controller.navigate(ReviewRoute(personId))
    fun external(uri: String, mime: String?) = controller.navigate(ExternalViewerRoute(uri, mime))
    fun finishOnboarding() = controller.navigate(HomeRoute) {
        popUpTo(OnboardingRoute) { inclusive = true }
    }
}

val LocalNavigator = staticCompositionLocalOf<Navigator> { error("No navigator") }

// Material 3 "shared axis X" transitions with emphasized easing for opening screens.
private val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
private val Standard = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val ENTER_MS = 500
private const val EXIT_MS = 350
private const val POP_MS = 420

private val slideIn: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(tween(ENTER_MS, easing = EmphasizedDecelerate)) { (it * 0.3f).toInt() } +
        fadeIn(tween(ENTER_MS / 2, delayMillis = 60, easing = LinearEasing))
}
private val slideOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(tween(ENTER_MS, easing = EmphasizedDecelerate)) { -(it * 0.1f).toInt() } +
        fadeOut(tween(EXIT_MS / 2, easing = LinearEasing)) +
        scaleOut(tween(ENTER_MS, easing = EmphasizedDecelerate), targetScale = 0.96f)
}
// Closing a screen is driven by the predictive back gesture: the transition is seeked with the
// finger, so every part must already move early in the gesture (no accelerate curves). The page
// shrinks into a rounded card and drifts to the side while the previous screen grows in behind
// it; only after letting go does it fade away.
private val popIn: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    scaleIn(tween(POP_MS, easing = Standard), initialScale = 0.92f) +
        slideInHorizontally(tween(POP_MS, easing = Standard)) { -(it * 0.06f).toInt() } +
        fadeIn(tween(POP_MS, easing = LinearEasing), initialAlpha = 0.35f)
}
private val popOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    scaleOut(tween(POP_MS, easing = Standard), targetScale = 0.86f) +
        slideOutHorizontally(tween(POP_MS, easing = Standard)) { (it * 0.18f).toInt() } +
        fadeOut(tween(POP_MS / 2, delayMillis = POP_MS / 2, easing = LinearEasing))
}
private val PageCorner = 32.dp

/**
 * Opaque page with the right content colour, so text is readable and pages never bleed through.
 * While it animates (e.g. during the back gesture) its corners round off like a card.
 */
@Composable
private fun AnimatedContentScope.Page(content: @Composable () -> Unit) {
    val corner by transition.animateDp(transitionSpec = { tween(POP_MS, easing = Standard) }, label = "pageCorner") {
        if (it == EnterExitState.Visible) 0.dp else PageCorner
    }
    Surface(
        color = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onBackground,
        shape = if (corner > 0.dp) RoundedCornerShape(corner) else RectangleShape,
        modifier = Modifier.fillMaxSize()
    ) {
        content()
    }
}

@Composable
fun LumenNavHost(startOnboarding: Boolean, onNavigatorReady: (Navigator) -> Unit) {
    val controller = rememberNavController()
    val navigator = remember(controller) { Navigator(controller) }
    // "Animation bei Zurück-Geste" off: going back switches screens instantly.
    val backAnimations = LocalAppSettings.current.backGestureAnimations
    val popEnter: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition =
        if (backAnimations) popIn else { { EnterTransition.None } }
    val popExit: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition =
        if (backAnimations) popOut else { { ExitTransition.None } }
    androidx.compose.runtime.LaunchedEffect(navigator) { onNavigatorReady(navigator) }
    SharedTransitionLayout {
        CompositionLocalProvider(LocalSharedTransitionScope provides this, LocalNavigator provides navigator) {
            NavHost(
                navController = controller,
                startDestination = if (startOnboarding) OnboardingRoute else HomeRoute,
                enterTransition = slideIn,
                exitTransition = slideOut,
                popEnterTransition = popEnter,
                popExitTransition = popExit,
            ) {
                composable<OnboardingRoute> {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { Page { OnboardingScreen() } }
                }
                composable<HomeRoute>(
                    exitTransition = {
                        if (targetState.destination.hasRoute<ViewerRoute>()) fadeOut(tween(300)) else slideOut()
                    },
                    popEnterTransition = {
                        when {
                            !backAnimations -> EnterTransition.None
                            initialState.destination.hasRoute<ViewerRoute>() ->
                                fadeIn(tween(300)) + scaleIn(tween(POP_MS, easing = Standard), initialScale = 0.96f)
                            else -> popIn()
                        }
                    },
                ) {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { Page { HomeScreen() } }
                }
                composable<ViewerRoute>(
                    enterTransition = { fadeIn(tween(250)) },
                    exitTransition = { fadeOut(tween(250)) },
                    popEnterTransition = { if (backAnimations) fadeIn(tween(250)) else EnterTransition.None },
                    // The viewer shrinks the photo with the finger itself (see ViewerScreen) and
                    // then lets it shrink a little further while fading out.
                    popExitTransition = {
                        if (backAnimations) fadeOut(tween(300)) + scaleOut(tween(300, easing = Standard), targetScale = 0.9f)
                        else ExitTransition.None
                    },
                ) { entry ->
                    val route = entry.toRoute<ViewerRoute>()
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { ViewerScreen(route.source, route.id) }
                }
                composable<ExternalViewerRoute>(
                    enterTransition = { fadeIn() + scaleIn(initialScale = 0.92f) },
                    popExitTransition = { if (backAnimations) popOut() else ExitTransition.None },
                ) { entry ->
                    val route = entry.toRoute<ExternalViewerRoute>()
                    ExternalViewerScreen(route.uri, route.mime)
                }
                composable<CollectionRoute> { entry ->
                    val route = entry.toRoute<CollectionRoute>()
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { Page { CollectionScreen(route.source, route.title) } }
                }
                composable<OptimizeRoute> { Page { OptimizeScreen() } }
                composable<DuplicatesRoute> {
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { Page { DuplicatesScreen() } }
                }
                composable<ModelsRoute> { Page { ModelsScreen() } }
                composable<SettingsRoute> { Page { SettingsScreen() } }
                composable<BackupRoute> { Page { BackupScreen() } }
                composable<VideoOptimizeRoute> { Page { VideoOptimizeScreen() } }
                composable<PersonRoute> { entry ->
                    CompositionLocalProvider(LocalNavAnimatedScope provides this) { Page { PersonScreen(entry.toRoute<PersonRoute>().id) } }
                }
                composable<ReviewRoute>(
                    enterTransition = { slideInVertically(tween(ENTER_MS, easing = EmphasizedDecelerate)) { it / 3 } + fadeIn(tween(250)) },
                    popExitTransition = {
                        if (backAnimations) scaleOut(tween(POP_MS, easing = Standard), targetScale = 0.9f) +
                            slideOutVertically(tween(POP_MS, easing = Standard)) { it / 4 } +
                            fadeOut(tween(POP_MS / 2, delayMillis = POP_MS / 2))
                        else ExitTransition.None
                    },
                ) { entry -> Page { ReviewScreen(entry.toRoute<ReviewRoute>().personId) } }
                composable<EditorRoute>(
                    enterTransition = { fadeIn() + scaleIn(initialScale = 0.94f) },
                    popExitTransition = { if (backAnimations) popOut() else ExitTransition.None },
                ) { entry -> EditorScreen(entry.toRoute<EditorRoute>().id) }
            }
        }
    }
}
