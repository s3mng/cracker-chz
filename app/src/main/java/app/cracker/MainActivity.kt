package app.cracker

import android.os.Bundle
import android.content.Intent
import androidx.activity.viewModels
import app.cracker.ui.home.HomeViewModel
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import app.cracker.ui.CrackerApp
import app.cracker.ui.theme.Ink
import app.cracker.ui.theme.CrackerTheme

class MainActivity : ComponentActivity() {
    private val homeViewModel: HomeViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) receiveShare(intent)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.Transparent.toArgb(), Color.Transparent.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(Ink.toArgb()),
        )
        setContent {
            CrackerTheme {
                CrackerApp(viewModel = homeViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveShare(intent)
    }

    private fun receiveShare(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND || intent.type != "text/plain") return
        intent.getStringExtra(Intent.EXTRA_TEXT)?.let(homeViewModel::receiveShare)
    }
}
