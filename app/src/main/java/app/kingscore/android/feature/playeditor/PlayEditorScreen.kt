package app.kingscore.android.feature.playeditor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kingscore.android.data.repository.*
import app.kingscore.android.ui.CatalogOption
import app.kingscore.android.ui.CatalogSelectionSheet
import app.kingscore.android.feature.plays.playerName
import app.kingscore.android.feature.plays.sourceLabel
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*

/** Caller owns the ViewModel scope and controls whether the editor is reachable. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayEditorRoute(model: PlayEditorViewModel, onResult: (PlayWriteResult) -> Unit = {}, onClose: () -> Unit) {
    val state by model.editor.collectAsStateWithLifecycle()
    var leaving by remember(model) { mutableStateOf(false) }
    var picking by remember(model) { mutableStateOf<String?>(null) }
    val focus = LocalFocusManager.current
    val list = rememberLazyListState()
    val close = {
        val result = model.editor.value.result
        model.clearMemory()
        result?.let(onResult)
        onClose()
    }
    val back = {
        if (!state.saving) {
            if (state.dirty && state.result == null && !state.accessBlocked) leaving = true else close()
        }
    }
    LaunchedEffect(model) { model.load() }
    LaunchedEffect(state.accessBlocked, state.canEdit) { if (!state.canEdit) picking = null; if (state.accessBlocked) leaving = false }
    LaunchedEffect(state.writeProblem) { if (state.writeProblem == PlayWriteProblem.INVALID_INPUT) list.animateScrollToItem(0) }
    BackHandler(onBack = back)
    Scaffold(modifier = Modifier.fillMaxSize().testTag("play-editor"), topBar = {
        TopAppBar(title = { Text(if (state.snapshot == null) "Nuova partita" else "Modifica partita") },
            navigationIcon = { IconButton(onClick = back, enabled = !state.saving) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Torna indietro")
            } })
    }) { padding ->
        LazyColumn(state = list, modifier = Modifier.fillMaxSize().padding(padding).imePadding().testTag("editor-content"),
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (state.accessBlocked) item { Text("Accesso bloccato. Connettiti a Internet e verifica nuovamente l'accesso.", Modifier.testTag("editor-blocked")) }
            else if (state.result != null) item {
                val result = state.result!!
                Notice(if (state.resultFromRecovery) "Operazione precedente recuperata." else when (result) {
                    is PlayWriteResult.Saved -> "Partita salvata."; is PlayWriteResult.Deleted -> "Partita eliminata."
                }, "editor-success")
                ResultDetail(result)
                Button(onClick = close, modifier = Modifier.fillMaxWidth().testTag("editor-done")) { Text("Chiudi") }
            } else {
                if (state.loading || state.saving) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator(Modifier.size(32.dp))
                        Text(if (state.saving) "Salvataggio in corso…" else "Caricamento…")
                    }
                }
                state.recovered?.let { result -> item {
                    Notice("È disponibile la conferma di un'operazione precedente.", "editor-restored")
                    ResultDetail(result)
                } }
                state.pending?.let { pending -> item {
                    Notice("Esito da verificare. Nuove scritture bloccate.", "editor-pending")
                    Text(when (pending.kind) {
                        PlayMutationKind.CREATE -> "Creazione precedente"; PlayMutationKind.UPDATE -> "Modifica precedente"
                        PlayMutationKind.DELETE -> "Eliminazione precedente"; null -> "Operazione precedente"
                    })
                    pending.playId?.let { Text("Partita: $it", style = MaterialTheme.typography.bodySmall) }
                    Text("Il recupero ripete la richiesta originale. Non salva eventuali nuove modifiche.")
                    Button(onClick = { model.recover(pending.requestId) }, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("editor-recover")) { Text("Verifica operazione precedente") }
                } }
                state.writeProblem?.let { problem -> item { Notice(writeMessage(problem), "editor-error", error = true) } }
                state.readProblem?.let { problem -> item { Notice(if (problem == DataProblem.FORBIDDEN)
                    "Non hai il permesso di leggere il catalogo." else "Non è stato possibile caricare i dati dell'editor.", "editor-read-error", error = true) } }
                if (state.readProblem != null || (state.draft == null && state.snapshot == null && !state.busy &&
                    state.writeProblem !in setOf(PlayWriteProblem.UNAVAILABLE, PlayWriteProblem.FORBIDDEN, PlayWriteProblem.STORAGE))) item {
                    Button(onClick = model::load, enabled = !state.busy, modifier = Modifier.testTag("editor-reload")) { Text("Riprova caricamento") }
                }
                if (state.conflict && state.pending == null) item {
                    Notice("La partita è cambiata. La tua bozza è conservata.", "editor-conflict")
                    OutlinedButton(onClick = model::readConflict, enabled = !state.busy, modifier = Modifier.testTag("editor-read-latest")) { Text("Leggi dati aggiornati") }
                }
                state.draft?.let { draft ->
                    item { Text("Dati della partita", style = MaterialTheme.typography.titleLarge) }
                    item {
                        OutlinedButton(onClick = { picking = "game" }, enabled = state.canEdit,
                            modifier = Modifier.fillMaxWidth().testTag("editor-game")) {
                            Text("Gioco: ${state.options.games.firstOrNull { it.id == draft.gameId }?.name ?: "Scegli gioco"}")
                        }
                        val dateValid = draft.playedAt.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")) &&
                            runCatching { LocalDate.parse(draft.playedAt).year in 1..9999 }.getOrDefault(false)
                        Field(draft.playedAt, { text -> model.edit { it.copy(playedAt = text) } }, "Data (AAAA-MM-GG)", "editor-date", state.canEdit,
                            invalid = !dateValid, help = "Inserisci una data valida, per esempio 2026-10-14.")
                        val durationValid = draft.duration.isEmpty() || (draft.duration.matches(Regex("0|[1-9][0-9]*")) && draft.duration.toIntOrNull() != null)
                        Field(draft.duration, { text -> model.edit { it.copy(duration = text) } }, "Durata in minuti", "editor-duration", state.canEdit,
                            invalid = !durationValid, help = "Lascia vuoto se non indicata; altrimenti usa minuti interi non negativi.", keyboard = KeyboardType.Number)
                        Text("Modalità", style = MaterialTheme.typography.titleMedium)
                        PlaySource.entries.forEach { source ->
                            Choice(sourceLabel(source.wireValue), draft.source == source, state.canEdit, "editor-source-${source.name}") { model.edit { it.copy(source = source) } }
                        }
                        OptionalField(draft.notes, { text -> model.edit { it.copy(notes = text) } }, "Note", "editor-notes", state.canEdit)
                        Check("Partita cooperativa", draft.cooperative, state.canEdit, "editor-cooperative") { cooperative ->
                            model.edit { it.copy(cooperative = cooperative, teamWon = if (cooperative) it.teamWon else null) }
                        }
                        if (draft.cooperative) {
                            Text("Esito della squadra", style = MaterialTheme.typography.titleMedium)
                            listOf(null to "Non indicato", true to "Vittoria", false to "Sconfitta").forEach { (won, label) ->
                                Choice(label, draft.teamWon == won, state.canEdit, "editor-team-$won") {
                                    model.edit { current -> current.copy(teamWon = won, scores = if (won == null) current.scores else current.scores.map { it.copy(winner = won) }) }
                                }
                            }
                        }
                    }
                    item {
                        Text("Partecipanti (${draft.scores.size})", style = MaterialTheme.typography.titleLarge)
                        Text("Un punteggio vuoto resta non indicato; zero è un punteggio.")
                    }
                    items(draft.scores, key = { "draft-${it.playerId}" }) { row ->
                        val name = playerName(state.options.players.firstOrNull { it.id == row.playerId })
                        Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(name, style = MaterialTheme.typography.titleMedium)
                            if (state.options.players.count { playerName(it) == name } > 1)
                                Text("Identificativo: ${row.playerId}", style = MaterialTheme.typography.bodySmall)
                            val update: ((DraftScore) -> DraftScore) -> Unit = { transform ->
                                model.edit { current -> current.copy(scores = current.scores.map { if (it.playerId == row.playerId) transform(it) else it }) }
                            }
                            val valid = runCatching {
                                require(row.score.isEmpty() || row.score.matches(Regex("^-?(0|[1-9][0-9]*)(\\.[0-9]+)?$")))
                                ScoreInput(row.id, row.playerId, row.score.takeIf { it.isNotEmpty() }?.let(::BigDecimal), row.winner, row.color, row.role)
                            }.isSuccess
                            Field(row.score, { text -> update { it.copy(score = text) } }, "Punteggio di $name", "editor-score-${row.playerId}", state.canEdit,
                                invalid = !valid, help = "Lascia vuoto se non indicato. Usa il punto per i decimali; sono ammessi valori negativi.")
                            Check("Vincitore: $name", row.winner, state.canEdit && (!draft.cooperative || draft.teamWon == null), "editor-winner-${row.playerId}") { value -> update { it.copy(winner = value) } }
                            OptionalField(row.color, { text -> update { it.copy(color = text) } }, "Colore di $name", "editor-color-${row.playerId}", state.canEdit)
                            OptionalField(row.role, { text -> update { it.copy(role = text) } }, "Ruolo di $name", "editor-role-${row.playerId}", state.canEdit)
                            TextButton(onClick = { model.edit { current -> current.copy(scores = current.scores.filterNot { it.playerId == row.playerId }) } },
                                enabled = state.canEdit, modifier = Modifier.testTag("editor-remove-${row.playerId}")) { Text("Rimuovi $name") }
                        } }
                    }
                    item {
                        OutlinedButton(onClick = { picking = "player" }, enabled = state.canEdit && draft.scores.size < 63,
                            modifier = Modifier.fillMaxWidth().testTag("editor-add-player")) { Text("Aggiungi partecipante") }
                        if (draft.scores.size == 63) Text("Raggiunto il limite di 63 partecipanti per questa versione.")
                    }
                    item {
                        Button(onClick = { focus.clearFocus(); model.save() }, enabled = state.canEdit && draft.gameId != null && draft.scores.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth().testTag("editor-save")) { Text("Salva partita") }
                    }
                }
                if (state.snapshot != null && state.draft == null) {
                    item { Notice("Questa partita storica non è modificabile. I dati sono mostrati senza conversioni.", "editor-legacy") }
                    snapshot(state.snapshot!!, state.options, "legacy")
                }
                state.latest?.let { latest ->
                    item { Text("Dati aggiornati", style = MaterialTheme.typography.titleLarge) }
                    snapshot(latest, state.options, "latest")
                    item { OutlinedButton(onClick = model::useLatest, enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth().testTag("editor-use-latest")) { Text("Scarta la bozza e usa questi dati") } }
                }
                if (state.snapshot != null) item {
                    OutlinedButton(onClick = model::requestDelete, enabled = state.canDelete,
                        modifier = Modifier.fillMaxWidth().testTag("editor-delete")) { Text("Elimina partita") }
                }
            }
        }
    }
    if (state.confirmingDelete && !state.accessBlocked) AlertDialog(onDismissRequest = model::cancelDelete,
        modifier = Modifier.testTag("editor-delete-dialog"), title = { Text("Eliminare la partita?") },
        text = { Text("La partita e i suoi punteggi saranno eliminati. Questa azione non può essere annullata.") },
        confirmButton = { TextButton(onClick = model::delete, modifier = Modifier.testTag("editor-confirm-delete")) { Text("Elimina partita") } },
        dismissButton = { TextButton(onClick = model::cancelDelete) { Text("Annulla") } })
    if (leaving && !state.accessBlocked) AlertDialog(onDismissRequest = { leaving = false }, modifier = Modifier.testTag("editor-exit-dialog"),
        title = { Text("Uscire dalla bozza?") }, text = { Text(if (state.pending != null) "La richiesta dall'esito incerto rimane da verificare al prossimo accesso."
            else "Le modifiche non salvate verranno scartate.") },
        confirmButton = { TextButton(onClick = close, modifier = Modifier.testTag("editor-confirm-exit")) { Text("Esci") } },
        dismissButton = { TextButton(onClick = { leaving = false }) { Text("Continua a modificare") } })
    val draft = state.draft
    if (picking != null && draft != null && state.canEdit) {
        val games = picking == "game"
        val options = if (games) state.options.games.map { CatalogOption(it.id, it.name, imageUrl = it.image_url) }
            else state.options.players.filterNot { player -> draft.scores.any { it.playerId == player.id } }.map {
                CatalogOption(it.id, playerName(it), imageUrl = it.avatar_url,
                    subtitle = it.name.takeIf { original -> original != playerName(it) },
                    searchText = "${it.name} ${it.alias.orEmpty()}", player = true)
            }
        CatalogSelectionSheet(title = if (games) "Scegli gioco" else "Scegli partecipante", options = options,
            selectedId = draft.gameId.takeIf { games }, searchLabel = if (games) "Cerca gioco" else "Cerca nome o alias",
            tagPrefix = "editor", modifier = Modifier.testTag("editor-picker"), onDismiss = { picking = null },
            helperText = if (games || draft.scores.isEmpty()) null else "${draft.scores.size} partecipanti già inseriti sono esclusi.",
            onChoose = { id ->
                if (id != null) {
                    model.edit { current -> if (games) current.copy(gameId = id)
                        else current.copy(scores = current.scores + DraftScore(playerId = id, winner = current.teamWon ?: false)) }
                    picking = null
                }
            })
    }
}

@Composable private fun Field(value: String, change: (String) -> Unit, label: String, tag: String, enabled: Boolean,
    invalid: Boolean = false, help: String = "", keyboard: KeyboardType = KeyboardType.Text) {
    OutlinedTextField(value, change, enabled = enabled, label = { Text(label) }, isError = invalid,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard), supportingText = { if (help.isNotEmpty()) Text(help) },
        singleLine = true, modifier = Modifier.fillMaxWidth().testTag(tag))
}
@Composable private fun OptionalField(value: String?, change: (String?) -> Unit, label: String, tag: String, enabled: Boolean) {
    OutlinedTextField(value.orEmpty(), change, enabled = enabled, label = { Text(label) },
        supportingText = { Text(if (value == null) "Non indicato" else if (value.isEmpty()) "Testo vuoto" else "Facoltativo") },
        modifier = Modifier.fillMaxWidth().testTag(tag))
    if (value != null) TextButton(onClick = { change(null) }, enabled = enabled, modifier = Modifier.testTag("$tag-clear")) { Text("Rimuovi $label") }
}
@Composable private fun Check(label: String, checked: Boolean, enabled: Boolean, tag: String, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}
@Composable private fun Choice(label: String, selected: Boolean, enabled: Boolean, tag: String, choose: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .selectable(selected, enabled = enabled, role = Role.RadioButton, onClick = choose), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null, enabled = enabled)
        Text(label, Modifier.weight(1f))
    }
}
@Composable private fun Notice(text: String, tag: String, error: Boolean = false) {
    Text(text, modifier = Modifier.fillMaxWidth().testTag(tag).semantics { liveRegion = LiveRegionMode.Polite },
        color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyLarge)
}
@Composable private fun ResultDetail(result: PlayWriteResult) {
    val id = when (result) { is PlayWriteResult.Saved -> result.value.play_id; is PlayWriteResult.Deleted -> result.value.play_id }
    Text("Partita: $id", Modifier.testTag("editor-result-id"))
    Text(if (result is PlayWriteResult.Deleted) "Torna allo storico per leggere l'elenco aggiornato."
        else "Riapri la partita per leggere il suo stato attuale.")
}
private fun LazyListScope.snapshot(snapshot: PlayEditorResult, options: HistoryOptions, tag: String) {
    val detail = snapshot.detail
    item(key = "$tag-play") { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(detail.game?.name ?: options.games.firstOrNull { it.id == detail.play.game_id }?.name ?: "Gioco non disponibile")
        Text("Data: ${detail.play.played_at} · ${sourceLabel(detail.play.source)}")
        Text("Durata: ${detail.play.duration_minutes?.let { "$it minuti" } ?: "Non indicata"}")
        Text("Note: ${detail.play.notes ?: "Non indicate"}")
        Text("Cooperativa: ${detail.play.is_cooperative?.let { if (it) "Sì" else "No" } ?: "Non indicato"}")
        Text("Esito squadra: ${detail.play.team_won?.let { if (it) "Vittoria" else "Sconfitta" } ?: "Non indicato"}")
        Text("${detail.scores.size} partecipanti", Modifier.testTag("editor-$tag-count"))
    } }
    items(detail.scores, key = { "$tag-${it.score.id}" }) { row ->
        Column(Modifier.fillMaxWidth().testTag("editor-$tag-score-${row.score.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(playerName(row.player ?: options.players.firstOrNull { it.id == row.score.player_id }), style = MaterialTheme.typography.titleMedium)
            Text("Punteggio: ${row.score.score?.toPlainString() ?: "Non indicato"}")
            Text("Vincitore: ${row.score.winner?.let { if (it) "Sì" else "No" } ?: "Non indicato"}")
            Text("Colore: ${row.score.color ?: "Non indicato"} · Ruolo: ${row.score.role ?: "Non indicato"}")
        }
    }
}
private fun writeMessage(problem: PlayWriteProblem) = when (problem) {
    PlayWriteProblem.INVALID_INPUT -> "Controlla data, durata, partecipanti e punteggi. Il testo potrebbe superare il limite consentito."
    PlayWriteProblem.CONFLICT -> "La partita risulta cambiata. Verifica i dati prima di proseguire."
    PlayWriteProblem.UNCERTAIN -> "Non è possibile confermare l'esito dell'operazione. Verifica prima di salvare di nuovo."
    PlayWriteProblem.STORAGE -> "Non è stato possibile proteggere o leggere la richiesta. Nuove scritture bloccate."
    PlayWriteProblem.FORBIDDEN -> "Non hai il permesso di gestire le partite."
    PlayWriteProblem.NOT_FOUND -> "Partita non disponibile."
    PlayWriteProblem.UNAVAILABLE -> "Gestione partite non disponibile in questa versione."
    PlayWriteProblem.INVALID_RESPONSE -> "Non è stato possibile leggere una risposta valida."
    PlayWriteProblem.CLOSED -> "Editor chiuso. Torna allo storico."
    PlayWriteProblem.BUSY -> "Un'altra operazione è in corso. Riprova quando è terminata."
}
