package app.lumen.photos

import android.content.ContentUris
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.WindowManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.lumen.photos.work.BackupWorker
import app.lumen.photos.work.FaceWorker
import app.lumen.photos.work.VideoOptimizeWorker
import app.lumen.photos.work.IndexWorker
import app.lumen.photos.work.OptimizeWorker
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import kotlinx.coroutines.flow.map
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.lumen.photos.ui.IntentSenderLauncher
import app.lumen.photos.ui.LocalAppSettings
import app.lumen.photos.ui.LocalIntentSenderLauncher
import app.lumen.photos.ui.navigation.LumenNavHost
import app.lumen.photos.ui.navigation.Navigator
import app.lumen.photos.ui.theme.LumenTheme

class MainActivity : ComponentActivity() {

    companion object {
        private const val DIM_AFTER_MS = 20_000L
        private const val DIM_BRIGHTNESS = 0.02f
    }

    private val launcher = IntentSenderLauncher(this)
    private var navigator: Navigator? = null
    private var pendingIntent: Intent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        window.isNavigationBarContrastEnforced = false
        if (savedInstanceState == null) pendingIntent = intent

        val c = container
        val startOnboarding = !c.settings.current.onboardingDone || !hasMediaPermission()
        if (!startOnboarding) c.media.start()

        observeBackgroundWork()
        disableSystemBackAnimationWhenRequested()

        setContent {
            val settings by c.settings.settings.collectAsStateWithLifecycle()
            LumenTheme(settings) {
                CompositionLocalProvider(
                    LocalIntentSenderLauncher provides launcher,
                    LocalAppSettings provides settings,
                ) {
                    LumenNavHost(startOnboarding = startOnboarding) { nav ->
                        navigator = nav
                        pendingIntent?.let { handleViewIntent(it) }
                        pendingIntent = null
                    }
                }
            }
        }
    }

    /**
     * With "Navigations-Animationen" off, back on the start screen is handled here: as long as the
     * app has a back callback, Android shows no predictive "back to home" preview either. Registered
     * before any screen, so every other back handler (NavHost, selections …) still comes first.
     */
    private fun disableSystemBackAnimationWhenRequested() {
        val rootBack = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                moveTaskToBack(true)
            }
        }
        onBackPressedDispatcher.addCallback(this, rootBack)
        lifecycleScope.launch {
            container.settings.settings.map { !it.backGestureAnimations }.distinctUntilChanged().collect { rootBack.isEnabled = it }
        }
    }

    // ---- Keep the display on (but dimmed) while background jobs run. ----

    private var workActive = false
    private var dimJob: Job? = null

    private fun observeBackgroundWork() {
        val wm = WorkManager.getInstance(this)
        val running = combine(
            wm.getWorkInfosForUniqueWorkFlow(IndexWorker.NAME),
            wm.getWorkInfosForUniqueWorkFlow(OptimizeWorker.NAME),
            wm.getWorkInfosForUniqueWorkFlow(BackupWorker.NAME),
            combine(
                wm.getWorkInfosForUniqueWorkFlow(FaceWorker.NAME),
                wm.getWorkInfosForUniqueWorkFlow(VideoOptimizeWorker.NAME),
            ) { a, b -> a + b },
            container.settings.settings,
        ) { index, optimize, backup, more, s ->
            s.keepScreenOnDuringWork && (index + optimize + backup + more).any { it.state == WorkInfo.State.RUNNING }
        }.distinctUntilChanged()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                running.collect { active ->
                    workActive = active
                    if (active) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        scheduleDim()
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        dimJob?.cancel()
                        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
                    }
                }
            }
        }
    }

    private fun scheduleDim() {
        dimJob?.cancel()
        dimJob = lifecycleScope.launch {
            delay(DIM_AFTER_MS)
            if (workActive) setBrightness(DIM_BRIGHTNESS)
        }
    }

    private fun setBrightness(value: Float) {
        val attrs = window.attributes
        if (attrs.screenBrightness != value) {
            attrs.screenBrightness = value
            window.attributes = attrs
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (workActive) {
            setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
            scheduleDim()
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (navigator != null) handleViewIntent(intent) else pendingIntent = intent
    }

    override fun onResume() {
        super.onResume()
        if (hasMediaPermission()) container.media.start()
    }

    fun hasMediaPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_IMAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /** Opens images shared/viewed from other apps (e.g. the camera's thumbnail). */
    private fun handleViewIntent(intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_VIEW && action != MediaStore.ACTION_REVIEW && action != "com.android.camera.action.REVIEW") return
        val uri = intent.data ?: return
        val nav = navigator ?: return
        val id = if (uri.authority == MediaStore.AUTHORITY) runCatching { ContentUris.parseId(uri) }.getOrNull() else null
        if (id != null && id > 0 && hasMediaPermission()) {
            nav.viewer(MediaListRegistry.SOURCE_TIMELINE, id)
        } else {
            nav.external(uri.toString(), intent.type)
        }
    }
}
