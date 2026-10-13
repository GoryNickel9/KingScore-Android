package app.kingscore.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import app.kingscore.android.feature.auth.AccessState
import app.kingscore.android.feature.auth.LoginScreen
import app.kingscore.android.feature.auth.message
import app.kingscore.android.feature.navigation.AuthenticatedNavigation
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.PlaysRepository
import app.kingscore.android.data.repository.RatingsRepository
import app.kingscore.android.data.repository.StatisticsRepository
import java.util.UUID
import app.kingscore.android.core.images.AccountImages
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun AppContent(
    access: AccessState,
    onSignIn: (String, String) -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onNetworkSettings: () -> Unit,
    playsFactory: ((UUID) -> PlaysRepository)? = null,
    onAccessFailure: (AuthProblem) -> Unit = {},
    statisticsFactory: ((UUID) -> StatisticsRepository)? = null,
    ratingsFactory: ((UUID) -> RatingsRepository)? = null,
    imagesFactory: ((UUID, (AuthProblem) -> Unit) -> AccountImages)? = null,
) {
    var email by rememberSaveable { mutableStateOf("") }
    val holder = rememberSaveableStateHolder()
    var previousAccount by rememberSaveable { mutableStateOf<String?>(null) }
    LaunchedEffect(access) {
        when (access) {
            is AccessState.Connected -> {
                val current = access.account.id.toString()
                previousAccount?.takeIf { it != current }?.let(holder::removeState)
                previousAccount = current
            }
            is AccessState.SignedOut, is AccessState.Blocked -> {
                previousAccount?.let {
                    holder.removeState(it)
                    email = ""
                }
                previousAccount = null
            }
            else -> Unit
        }
    }
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
            when (access) {
                AccessState.Checking -> Column(horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.testTag("checking-session")) {
                    CircularProgressIndicator()
                    Text("Verifica accesso…")
                }
                is AccessState.SignedOut -> LoginScreen(email, { email = it }, access.problem, onSignIn)
                is AccessState.Connected -> holder.SaveableStateProvider(access.account.id.toString()) {
                    val images = remember(access.account.id, imagesFactory) { imagesFactory?.invoke(access.account.id, onAccessFailure) }
                    DisposableEffect(images) { onDispose { images?.close() } }
                    CompositionLocalProvider(LocalAccountImages provides images) {
                        AuthenticatedNavigation(access.account, onSignOut, playsFactory, onAccessFailure, statisticsFactory, ratingsFactory)
                    }
                }
                is AccessState.Blocked -> Column(Modifier.padding(28.dp).verticalScroll(rememberScrollState()).testTag("connection-blocked"),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(access.problem.message(), style = MaterialTheme.typography.headlineSmall)
                    if (playsFactory != null) Text("Se avevi inviato una modifica, verifica i commenti prima di ripetere l'operazione.")
                    Button(onClick = onRetry) { Text("Riprova") }
                    TextButton(onClick = onNetworkSettings) { Text("Impostazioni rete") }
                    TextButton(onClick = onSignOut) { Text("Esci dall’account") }
                }
            }
        }
    }
}
