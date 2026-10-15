package app.kingscore.android.feature.navigation

import android.content.Intent
import android.content.ActivityNotFoundException
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.kingscore.android.core.auth.Account
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.data.repository.PlaysRepository
import app.kingscore.android.data.repository.StatisticsRepository
import app.kingscore.android.data.repository.RatingsRepository
import app.kingscore.android.feature.dashboard.DashboardRoute
import app.kingscore.android.feature.stats.StatisticsRoute
import app.kingscore.android.feature.ratings.RatingsRoute
import app.kingscore.android.feature.plays.HistoryRoute
import app.kingscore.android.feature.plays.DetailRoute
import app.kingscore.android.feature.games.GameRoute
import app.kingscore.android.feature.players.PlayerRoute
import androidx.navigation.NavType
import androidx.navigation.navArgument
import java.util.UUID
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kingscore.android.ui.modelFactory
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kingscore.android.core.network.NetworkMonitor
import app.kingscore.android.data.repository.PlayWrites
import app.kingscore.android.feature.playeditor.PlayEditorRoute
import app.kingscore.android.feature.playeditor.PlayEditorViewModel

private data class Destination(val route: String, val label: String, val icon: ImageVector)
private val destinations = listOf(
    Destination("home", "Home", Icons.Outlined.Home),
    Destination("plays", "Partite", Icons.AutoMirrored.Outlined.EventNote),
    Destination("stats", "Statistiche", Icons.Outlined.BarChart),
    Destination("elo", "ELO", Icons.Outlined.EmojiEvents),
    Destination("account", "Profilo", Icons.Outlined.PersonOutline),
)
private const val BASE_NAVIGATION_HEIGHT_DP = 80f

@Composable
fun AuthenticatedNavigation(account: Account, onSignOut: () -> Unit,
    playsFactory: ((UUID) -> PlaysRepository)? = null, onAccessFailure: (AuthProblem) -> Unit = {},
    statisticsFactory: ((UUID) -> StatisticsRepository)? = null, ratingsFactory: ((UUID) -> RatingsRepository)? = null,
    playWritesFactory: ((UUID) -> PlayWrites)? = null, editorNetwork: NetworkMonitor? = null) {
    key(account.id, account.isAdmin, playWritesFactory != null && editorNetwork != null) {
    val navigation = rememberNavController()
    val entry by navigation.currentBackStackEntryAsState()
    val context = LocalContext.current
    val navigationHeight = (BASE_NAVIGATION_HEIGHT_DP * LocalDensity.current.fontScale.coerceAtLeast(1f)).dp
    var browserUnavailable by remember { mutableStateOf(false) }
    val resourcesModel: AuthenticatedResources = viewModel(factory = modelFactory { AuthenticatedResources() })
    val editorAvailable = playWritesFactory != null && editorNetwork != null
    val resources = remember(resourcesModel, account.id, account.isAdmin, editorAvailable) {
        resourcesModel.acquire(account.id, playsFactory, statisticsFactory, ratingsFactory, account.isAdmin, editorAvailable)
    }
    val repository = resources.plays
    val statistics = resources.statistics
    val ratings = resources.ratings
    val revision by resources.playDataRevision.collectAsStateWithLifecycle()
    val canEditPlays = account.isAdmin && repository != null && playWritesFactory != null && editorNetwork != null
    val editing = entry?.destination?.route in setOf("play/new", "play/edit/{id}")
    val activity = context.activity()
    DisposableEffect(resources) { onDispose {
        if (activity?.isChangingConfigurations != true) resourcesModel.release(resources)
    } }
    CompositionLocalProvider(LocalViewModelStoreOwner provides resources, LocalPlayDataRevision provides revision) {
        Scaffold(
            bottomBar = {
                if (!editing) NavigationBar(modifier = Modifier.heightIn(min = navigationHeight)) {
                    destinations.forEach { destination ->
                        NavigationBarItem(
                            selected = entry?.destination?.route == destination.route ||
                                (destination.route == "plays" && entry?.destination?.route in listOf("play/{id}", "game/{id}", "player/{id}")),
                            onClick = {
                                navigation.navigate(destination.route) {
                                    popUpTo(navigation.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(destination.icon, contentDescription = null) },
                            label = { Text(destination.label, maxLines = 3) },
                        )
                    }
                }
            },
            modifier = Modifier.testTag("authenticated-shell"),
        ) { padding ->
            NavHost(navigation, startDestination = "home", modifier = Modifier.padding(padding)) {
                destinations.forEach { destination ->
                    composable(destination.route) {
                        if (destination.route == "plays" && repository != null) {
                            HistoryRoute(repository, onAccessFailure,
                                onCreate = if (canEditPlays) ({ navigation.navigate("play/new") }) else null) { id -> navigation.navigate("play/$id") }
                        } else if (destination.route == "home" && repository != null) {
                            DashboardRoute(account, repository, onAccessFailure,
                                onOpen = { navigation.navigate("play/$it") }, onHistory = { navigation.navigate("plays") },
                                onRatings = { navigation.navigate("elo") }, statisticsRepository = statistics,
                                onProfile = { navigation.navigate("account") { launchSingleTop = true } })
                        } else if (destination.route == "stats" && statistics != null) {
                            StatisticsRoute(statistics, onAccessFailure)
                        } else if (destination.route == "elo" && ratings != null) {
                            RatingsRoute(account, ratings, onAccessFailure,
                                onProfile = { navigation.navigate("account") { launchSingleTop = true } })
                        } else {
                            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
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
                composable("play/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { detailEntry ->
                    val id = runCatching { UUID.fromString(detailEntry.arguments?.getString("id")) }.getOrNull()
                    if (repository != null && id != null) DetailRoute(repository, account.id, id, onAccessFailure,
                        onOpenGame = { gameId ->
                            // Reopening the parent game returns to its existing pages instead of stacking duplicates.
                            if (!navigation.popBackStack("game/$gameId", false)) navigation.navigate("game/$gameId")
                        }, onOpenPlayer = { playerId ->
                            if (!navigation.popBackStack("player/$playerId", false)) navigation.navigate("player/$playerId")
                        }, onManage = if (canEditPlays) ({ navigation.navigate("play/edit/$id") }) else null) { navigation.popBackStack() }
                    else Text("Partita non disponibile.", modifier = Modifier.padding(24.dp))
                }
                composable("game/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { gameEntry ->
                    val id = runCatching { UUID.fromString(gameEntry.arguments?.getString("id")) }.getOrNull()
                    if (repository != null && id != null) GameRoute(repository, id, onAccessFailure,
                        onOpenPlay = { navigation.navigate("play/$it") }) { navigation.popBackStack() }
                    else Text("Gioco non disponibile.", modifier = Modifier.padding(24.dp))
                }
                composable("player/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { playerEntry ->
                    val id = runCatching { UUID.fromString(playerEntry.arguments?.getString("id")) }.getOrNull()
                    if (repository != null && id != null) PlayerRoute(repository, account.id, id, onAccessFailure,
                        onOpenPlay = { navigation.navigate("play/$it") }) { navigation.popBackStack() }
                    else Text("Giocatore non disponibile.", modifier = Modifier.padding(24.dp))
                }
                composable("play/new") {
                    EditorDestination(account, null, repository, playWritesFactory, editorNetwork, canEditPlays,
                        onAccessFailure, resources::playSaved) { navigation.popBackStack() }
                }
                composable("play/edit/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { editorEntry ->
                    val id = runCatching { UUID.fromString(editorEntry.arguments?.getString("id")) }.getOrNull()
                    EditorDestination(account, id, repository, playWritesFactory, editorNetwork, canEditPlays && id != null,
                        onAccessFailure, resources::playSaved) { navigation.popBackStack() }
                }
            }
        }
    }
    }
}

@Composable
private fun EditorDestination(account: Account, id: UUID?, reads: PlaysRepository?,
    factory: ((UUID) -> PlayWrites)?, network: NetworkMonitor?, allowed: Boolean,
    onAccessFailure: (AuthProblem) -> Unit, onSaved: () -> Unit, onClose: () -> Unit) {
    if (allowed && reads != null && factory != null && network != null) {
        val model: PlayEditorViewModel = viewModel(factory = modelFactory {
            PlayEditorViewModel(account, network, reads, factory(account.id), id, enabled = true, onAccessFailure = onAccessFailure)
        })
        PlayEditorRoute(model, onResult = { onSaved() }, onClose = onClose)
    } else Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("La gestione delle partite non è disponibile.")
        TextButton(onClick = onClose) { Text("Torna indietro") }
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}
