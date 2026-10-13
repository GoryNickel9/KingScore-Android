package app.kingscore.android

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.SystemBarStyle
import android.graphics.Color
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kingscore.android.feature.auth.AppViewModel
import app.kingscore.android.feature.auth.AccessState
import app.kingscore.android.ui.AppContent
import app.kingscore.android.ui.KingScoreTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT))
        val app = application as KingScoreApplication
        setContent {
            KingScoreTheme {
                val auth = app.auth
                if (auth == null) {
                    Text("Accesso temporaneamente non disponibile.")
                } else {
                    val model: AppViewModel = viewModel(factory = object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            @Suppress("UNCHECKED_CAST")
                            return AppViewModel(auth, app.network) as T
                        }
                    })
                    val state by model.access.collectAsStateWithLifecycle()
                    LaunchedEffect(state) { if (state is AccessState.SignedOut) app.clearCommentOutcomes() }
                    val lifecycle = LocalLifecycleOwner.current.lifecycle
                    LaunchedEffect(model, lifecycle) {
                        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                            model.onForeground()
                            while (isActive) {
                                delay(60_000)
                                model.verifyActiveSession()
                            }
                        }
                    }
                    AppContent(state, model::signIn, model::retry, { app.clearCommentOutcomes(); model.signOut() },
                        onNetworkSettings = { startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS)) },
                        playsFactory = app::plays, onAccessFailure = model::rejectAccess,
                        statisticsFactory = app::statistics, ratingsFactory = app::ratings, imagesFactory = app::images)
                }
            }
        }
    }
}
