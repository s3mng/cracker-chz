package app.cracker.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import app.cracker.CrackerApplication
import app.cracker.auth.NaverLoginScreen
import app.cracker.download.DownloadLocationStore
import app.cracker.ui.home.HomeScreen
import app.cracker.ui.home.HomeViewModel
import app.cracker.ui.settings.SettingsScreen

@Composable
fun CrackerApp(
    viewModel: HomeViewModel = viewModel(),
) {
    val navController = rememberNavController()
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(state.shareRevision) {
        if (state.shareRevision > 0) navController.popBackStack("home", inclusive = false)
    }
    val app = context.applicationContext as CrackerApplication
    val folderUri by app.graph.downloadLocation.uri.collectAsStateWithLifecycle()
    val vodRetries by app.graph.transferSettings.vodRetries.collectAsStateWithLifecycle()
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            app.graph.downloadLocation.set(uri)
        }
    }

    NavHost(navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                viewModel = viewModel,
                onOpenSettings = { navController.navigate("settings") },
                onLogin = { navController.navigate("login") },
            )
        }
        composable("settings") {
            SettingsScreen(
                isLoggedIn = state.isLoggedIn,
                folderLabel = if (folderUri == null) {
                    DownloadLocationStore.DEFAULT_LABEL
                } else {
                    app.graph.downloadLocation.label()
                },
                customFolder = folderUri != null,
                vodRetries = vodRetries,
                onBack = { navController.popBackStack() },
                onLogin = { navController.navigate("login") },
                onLogout = viewModel::logout,
                onPickFolder = { folderPicker.launch(folderUri) },
                onResetFolder = app.graph.downloadLocation::clear,
                onVodRetriesChange = app.graph.transferSettings::setVodRetries,
            )
        }
        composable("login") {
            NaverLoginScreen(
                store = app.graph.cookies,
                onDone = { navController.popBackStack() },
            )
        }
    }
}
