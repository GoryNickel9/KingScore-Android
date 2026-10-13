package app.kingscore.android.feature.navigation

import android.content.Intent
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.automirrored.outlined.EventNote
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PersonOutline
import androidx.compose.material.icons.outlined.EmojiEvents
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.kingscore.android.core.auth.Account

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("home", "Home", Icons.Outlined.Home),
    Destination("plays", "Partite", Icons.AutoMirrored.Outlined.EventNote),
    Destination("stats", "Statistiche", Icons.Outlined.BarChart),
    Destination("elo", "ELO", Icons.Outlined.EmojiEvents),
    Destination("account", "Profilo", Icons.Outlined.PersonOutline),
)

@Composable
fun AuthenticatedNavigation(account: Account, onSignOut: () -> Unit) {
    val navigation = rememberNavController()
    val entry by navigation.currentBackStackEntryAsState()
    val context = LocalContext.current
    var browserUnavailable by remember { mutableStateOf(false) }
    Scaffold(
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = entry?.destination?.route == destination.route,
                        onClick = {
                            navigation.navigate(destination.route) {
                                popUpTo(navigation.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label, maxLines = 1) },
                    )
                }
            }
        },
        modifier = Modifier.testTag("authenticated-shell"),
    ) { padding ->
        NavHost(navigation, startDestination = "home", modifier = Modifier.padding(padding)) {
            destinations.forEach { destination ->
                composable(destination.route) {
                    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Text(destination.label, style = MaterialTheme.typography.headlineLarge)
                        if (destination.route == "account") {
                            Text(account.name, style = MaterialTheme.typography.titleLarge)
                            account.email?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            Text(if (account.isAdmin) "Amministratore" else "Giocatore", modifier = Modifier.testTag("account-role"))
                            HorizontalDivider()
                            TextButton(onClick = {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://partyscore.baldeddu.cc/")))
                                } catch (_: ActivityNotFoundException) {
                                    browserUnavailable = true
                                }
                            }) { Text("Apri PartyScore") }
                            if (browserUnavailable) Text("Installa un browser per aprire PartyScore.", color = MaterialTheme.colorScheme.error)
                            Button(onClick = onSignOut, modifier = Modifier.testTag("sign-out")) { Text("Esci") }
                        } else {
                            Text("Versione di anteprima", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                            Text("Le schermate dei risultati saranno disponibili nel prossimo aggiornamento.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                            if (destination.route == "home") Text("Ciao, ${account.name}.")
                            if (destination.route == "plays" && account.isAdmin) {
                                Text("La gestione delle partite non è ancora disponibile in questa versione.")
                            }
                        }
                    }
                }
            }
        }
    }
}
