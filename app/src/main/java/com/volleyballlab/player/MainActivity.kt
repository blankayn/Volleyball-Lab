package com.volleyballlab.player

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
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

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the platform hands the splash window
        // over rather than drawing the plain theme first.
        installSplashScreen()
        super.onCreate(savedInstanceState)

        // Edge to edge with transparent bars. The scaffolds inside consume the
        // insets themselves, which matters most on the capture screen: the camera
        // preview is meant to run under the status bar, and letting the system
        // pad it would letterbox the viewfinder.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(TRANSPARENT, TRANSPARENT),
        )

        setContent {
            val darkTheme = isSystemInDarkTheme()
            SideEffect {
                // The window keeps the splash colour underneath Compose so that a
                // configuration change or a slow first frame shows brand colour
                // rather than a white flash.
                window.setBackgroundDrawableResource(R.color.splash_background)
            }
            VolleyballLabTheme(darkTheme = false) {
                VolleyballLabApp()
            }
        }
    }

    private companion object {
        /**
         * `SystemBarStyle.auto` needs the scrim colours it should use in light and
         * dark. Passing transparent for both opts out of the scrim entirely, which
         * is what edge-to-edge content wants; icon contrast is still handled
         * automatically from the theme.
         */
        const val TRANSPARENT = android.graphics.Color.TRANSPARENT
    }
}
