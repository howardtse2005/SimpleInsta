package app.simpleinsta.ig

import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.webkit.CookieManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import app.simpleinsta.ig.ui.AppRoot
import app.simpleinsta.ig.ui.SimpleInstaTheme
import app.simpleinsta.ig.ui.windowColorFor

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as SimpleInstaApp

        setContent {
            val reported by app.darkTheme.collectAsState()
            // System dark mode wins: a phone in dark mode should get a dark
            // app even if the Instagram session happens to be on light.
            val dark = isSystemInDarkTheme() || reported == true

            // The chrome sits directly above and below the page. If the page is
            // dark and the bars are light, the app looks broken.
            LaunchedEffect(dark) {
                val color = windowColorFor(dark)
                window.setBackgroundDrawable(ColorDrawable(color))
                window.statusBarColor = color
                window.navigationBarColor = color
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }

            SimpleInstaTheme(dark = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AppRoot()
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // Flush cookies to disk so the Instagram session survives app restarts.
        // Sign-in is a one-time thing; this is what makes it stick.
        runCatching { CookieManager.getInstance().flush() }
    }
}
