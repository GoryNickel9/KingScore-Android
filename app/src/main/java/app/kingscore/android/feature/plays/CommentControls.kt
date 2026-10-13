package app.kingscore.android.feature.plays

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.kingscore.android.data.repository.CommentProblem
import java.util.UUID
import kingscore.contracts.v1.UpdateComment

data class CommentActions(val compose: (UUID?) -> Unit, val change: (String) -> Unit, val save: () -> Unit,
    val requestDelete: (UUID) -> Unit, val delete: () -> Unit, val dismiss: () -> Unit, val recheck: () -> Unit)

@Composable
internal fun CommentControls(state: DetailState, actions: CommentActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.commentNotice?.let {
            Text(when (it) { CommentNotice.CREATED -> "Commento inviato."; CommentNotice.UPDATED -> "Commento modificato.";
                CommentNotice.DELETED -> "Commento eliminato."
                CommentNotice.RECONCILED -> "Verifica conclusa. Controlla i commenti prima di ripetere l'operazione." },
                modifier = Modifier.testTag("comment-success").semantics { liveRegion = LiveRegionMode.Polite })
        }
        if (state.commentWriteProblem != null && state.commentEditor == null && state.deletingComment == null) {
            CommentError(state, actions)
        }
        OutlinedButton(onClick = { actions.compose(null) }, enabled = !state.commentSaving && !state.commentsLoading &&
            state.commentWriteProblem != CommentProblem.UNCERTAIN, modifier = Modifier.testTag("add-comment")) { Text("Scrivi un commento") }
    }
}

@Composable
internal fun CommentDialogs(state: DetailState, actions: CommentActions) {
    if (state.commentEditor == null && state.deletingComment == null) return
    Dialog(onDismissRequest = { if (!state.commentSaving) actions.dismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !state.commentSaving,
            dismissOnClickOutside = !state.commentSaving)) {
        BoxWithConstraints(Modifier.fillMaxWidth().safeDrawingPadding().imePadding().padding(16.dp)) {
            Surface(Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight),
                shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).testTag("comment-dialog"),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    val editor = state.commentEditor
                    Text(if (editor == null) "Elimina commento" else if (editor.id == null) "Scrivi un commento" else "Modifica commento",
                        style = MaterialTheme.typography.titleLarge)
                    if (editor != null) {
                        val normalized = editor.content.trim()
                        val valid = runCatching { UpdateComment(normalized) }.isSuccess
                        val count = normalized.codePointCount(0, normalized.length)
                        OutlinedTextField(editor.content, actions.change, enabled = !state.commentSaving, minLines = 2, maxLines = 5,
                            label = { Text("Commento") }, isError = editor.content.isNotEmpty() && !valid,
                            supportingText = { Text("$count / 1.000 caratteri") }, modifier = Modifier.fillMaxWidth().testTag("comment-input"))
                        if (editor.id != null) Text("La modifica più recente prevale se il commento viene aggiornato da un altro dispositivo.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        CommentError(state, actions)
                        Button(onClick = actions.save, enabled = valid && !state.commentSaving && !state.commentsLoading &&
                            state.commentWriteProblem != CommentProblem.UNCERTAIN &&
                            (editor.id == null || state.comments.any { it.id == editor.id }),
                            modifier = Modifier.fillMaxWidth().testTag("save-comment")) {
                            Text(if (state.commentSaving) "Salvataggio…" else if (editor.id == null) "Invia commento" else "Salva modifica")
                        }
                    } else {
                        Text("Vuoi eliminare il tuo commento? L'operazione non può essere annullata.")
                        state.comments.firstOrNull { it.id == state.deletingComment }?.let { Text(it.content) }
                        CommentError(state, actions)
                        Button(onClick = actions.delete, enabled = !state.commentSaving && !state.commentsLoading &&
                            state.commentWriteProblem != CommentProblem.UNCERTAIN && state.comments.any { it.id == state.deletingComment },
                            modifier = Modifier.fillMaxWidth().testTag("confirm-delete-comment")) {
                            Text(if (state.commentSaving) "Eliminazione…" else "Elimina commento")
                        }
                    }
                    TextButton(onClick = actions.dismiss, enabled = !state.commentSaving,
                        modifier = Modifier.fillMaxWidth().testTag("cancel-comment")) { Text("Annulla") }
                }
            }
        }
    }
}

@Composable
private fun CommentError(state: DetailState, actions: CommentActions) {
    state.commentWriteProblem?.let { problem ->
        Text(when (problem) {
            CommentProblem.INVALID_CONTENT -> "Usa da 1 a 1.000 caratteri validi. La partita deve essere ancora disponibile."
            CommentProblem.FORBIDDEN -> "Non hai il permesso di modificare questo commento."
            CommentProblem.NOT_FOUND -> "Commento assente o non più modificabile. Rileggi i commenti."
            CommentProblem.UNCERTAIN -> "Esito non confermato. Il commento potrebbe essere già stato salvato o eliminato. Rileggi prima di ripetere l'operazione."
        }, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("comment-write-error").semantics { liveRegion = LiveRegionMode.Polite })
        if (problem == CommentProblem.UNCERTAIN || problem == CommentProblem.NOT_FOUND) {
            OutlinedButton(onClick = actions.recheck, enabled = !state.commentsLoading && !state.commentSaving,
                modifier = Modifier.testTag("recheck-comments")) { Text("Rileggi commenti") }
            if (state.commentsLoading) Text("Verifica commenti…")
            if (state.commentsProblem != null) Text("Verifica non riuscita. Riprova a rileggere i commenti.", color = MaterialTheme.colorScheme.error)
        }
    }
}
