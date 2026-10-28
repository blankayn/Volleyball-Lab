package com.volleyballlab.player

import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.volleyballlab.player.feature.notifications.NotificationInboxDialog
import com.volleyballlab.player.data.notifications.TrainingNotificationWorker
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.volleyballlab.player.navigation.VolleyballLabApp
import com.volleyballlab.player.ui.theme.VolleyballLabTheme

/**
 * The app's single activity.
 *
 * Everything else is Compose. There is no fragment, no second activity and no
 * XML layout, which is what makes the navigation graph in
 * [com.volleyballlab.player.navigation] the complete and only description of how
 * the app moves between screens.
 */
class MainActivity : ComponentActivity() {
    private val showNotificationInbox = mutableStateOf(false)

    override fun onResume() {
        super.onResume()
        (application as VolleyballLabApplication).container.coachPresenceReporter.foreground(true)
    }

    override fun onPause() {
        (application as VolleyballLabApplication).container.coachPresenceReporter.foreground(false)
        super.onPause()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        (application as VolleyballLabApplication).container.coachPresenceReporter.interact()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (opensInbox(intent)) showNotificationInbox.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the platform hands the splash window
        // over rather than drawing the plain theme first.
        val launchSplash = installSplashScreen()
        super.onCreate(savedInstanceState)
        // Hand straight to the first Compose frame, which is the branded splash.
        launchSplash.setOnExitAnimationListener { it.remove() }
        showNotificationInbox.value = opensInbox(intent)

        // Edge to edge with transparent bars. The scaffolds inside consume the
        // insets themselves, which matters most on the capture screen: the camera
        // preview is meant to run under the status bar, and letting the system
        // pad it would letterbox the viewfinder.
        enableEdgeToEdge(
            // The app uses a light palette regardless of Android's appearance
            // setting, so use dark system icons over its light surfaces.
            statusBarStyle = SystemBarStyle.light(TRANSPARENT, TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(TRANSPARENT, TRANSPARENT),
        )

        setContent {
            SideEffect {
                // The window keeps the splash colour underneath Compose so that a
                // configuration change or a slow first frame shows brand colour
                // rather than a white flash.
                window.setBackgroundDrawableResource(R.color.splash_background)
            }
            VolleyballLabTheme(darkTheme = false) {
                VolleyballLabApp()
                if (showNotificationInbox.value) NotificationInboxDialog { showNotificationInbox.value = false }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    withContext(Dispatchers.Default) { TrainingNotificationWorker.schedule(applicationContext) }
                    repeatOnLifecycle(Lifecycle.State.STARTED) {
                        (application as VolleyballLabApplication).container.observeNotifications()
                    }
                }
            }
        }
    }

    private fun opensInbox(intent: Intent): Boolean =
        intent.getBooleanExtra("open_notifications", false) || intent.action == ACTION_OPEN_NOTIFICATIONS

    private companion object {
        const val ACTION_OPEN_NOTIFICATIONS = "com.volleyballlab.player.OPEN_NOTIFICATIONS"

        /**
         * Transparent bars keep edge-to-edge content. Their light or dark icon
         * appearance is managed by the active player or coach shell.
         */
        const val TRANSPARENT = android.graphics.Color.TRANSPARENT
    }
}
