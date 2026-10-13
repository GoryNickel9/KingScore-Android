package app.kingscore.android.ui

import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.core.auth.*
import app.kingscore.android.data.repository.*
import app.kingscore.android.feature.auth.AccessState
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kingscore.contracts.v1.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CommentsUiTest {
    @get:Rule val compose = createComposeRule()
    private val repo = FakePlays()
    private val access = mutableStateOf<AccessState>(AccessState.Connected(account))

    @Test fun createEditAndConfirmedDeleteKeepAuthorOwnershipAndServerRows() {
        show(); openDetail(); add()
        compose.onNodeWithTag("comment-input").performClick().performTextInput("  Bella partita\n😀  ")
        capture("phase40-editor", dialog = true)
        save()
        commentButton("edit-comment-${created}")
        assertEquals(listOf("create:Bella partita\n😀"), repo.calls)
        compose.onNodeWithTag("comment-input").performTextReplacement("Modificato 😀")
        save()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Modificato 😀"))
        compose.onNodeWithText("Modificato 😀").assertIsDisplayed()
        capture("phase40-comments")
        commentButton("delete-comment-$created")
        compose.onNodeWithTag("cancel-comment").performScrollTo().performClick()
        assertEquals(2, repo.calls.size)
        commentButton("delete-comment-$created")
        compose.onNodeWithTag("confirm-delete-comment").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("comment-dialog").assertDoesNotExist()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("comment-success"))
        compose.onNodeWithText("Commento eliminato.").assertIsDisplayed()
        assertEquals(listOf("create:Bella partita\n😀", "update:Modificato 😀", "delete"), repo.calls)
        assertFalse(repo.rows.any { it.id == created })
    }

    @Test fun adminHasNoControlsForOtherAuthorsAndDoesNotNeedLinkedPlayer() {
        show(); openDetail()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Altrui"))
        compose.onNodeWithTag("edit-comment-${other.id}").assertDoesNotExist()
        compose.onNodeWithTag("delete-comment-${other.id}").assertDoesNotExist()
        commentButton("edit-comment-${own.id}")
        compose.onNodeWithTag("comment-input").assertExists()
        compose.onNodeWithTag("cancel-comment").performScrollTo().performClick()
        add()
        compose.onNodeWithTag("comment-input").assertExists()
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun unicodeLimitAndBlankInputDisableSubmissionWithoutTruncation() {
        show(); openDetail(); add()
        compose.onNodeWithTag("save-comment").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("comment-input").performTextReplacement("   ")
        compose.onNodeWithTag("save-comment").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("comment-input").performTextReplacement("😀".repeat(1001))
        compose.onNodeWithText("1001 / 1.000 caratteri").assertExists()
        compose.onNodeWithTag("save-comment").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("comment-input").performTextReplacement("😀".repeat(1000))
        compose.onNodeWithText("1000 / 1.000 caratteri").assertExists()
        compose.onNodeWithTag("save-comment").performScrollTo().assertIsEnabled()
        assertTrue(repo.calls.isEmpty())
    }

    @Test fun uncertainCommittedCreateRequiresRereadWithoutAutomaticRepeat() {
        repo.failAfterCommit = true
        show(); openDetail(); add()
        compose.onNodeWithTag("comment-input").performTextInput("Già salvato")
        compose.onNodeWithTag("save-comment").performScrollTo().performClick()
        compose.onNodeWithTag("comment-write-error").assertExists()
        compose.onNodeWithTag("save-comment").performScrollTo().assertIsNotEnabled()
        capture("phase40-uncertain", dialog = true)
        assertEquals(1, repo.calls.size)
        compose.onNodeWithTag("recheck-comments").performScrollTo().performClick()
        compose.onNodeWithTag("comment-write-error").assertDoesNotExist()
        compose.onNodeWithTag("cancel-comment").performScrollTo().performClick()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Già salvato"))
        compose.onNodeWithText("Già salvato").assertIsDisplayed()
        assertEquals(1, repo.calls.size)
    }

    @Test fun forbiddenWriteAndMissingDeleteNeverShowSuccess() {
        show(); openDetail(); commentButton("edit-comment-${own.id}")
        repo.problem = CommentProblem.FORBIDDEN
        compose.onNodeWithTag("comment-input").performTextReplacement("Tentativo")
        compose.onNodeWithTag("save-comment").performScrollTo().performClick()
        compose.onNodeWithText("Non hai il permesso di modificare questo commento.").assertExists()
        compose.onNodeWithTag("cancel-comment").performScrollTo().performClick()
        repo.problem = CommentProblem.NOT_FOUND
        commentButton("delete-comment-${own.id}")
        compose.onNodeWithTag("confirm-delete-comment").performScrollTo().performClick()
        compose.onNodeWithText("Commento assente o non più modificabile. Rileggi i commenti.").assertExists()
        compose.onNodeWithTag("comment-success").assertDoesNotExist()
        compose.onNodeWithTag("authenticated-shell").assertIsDisplayed()
    }

    @Test fun savingDisablesRepeatAndCancelAndLateResultCannotRestoreOfflineAccount() {
        val pending = CompletableDeferred<Unit>()
        repo.pending = pending
        show(); openDetail(); add()
        compose.onNodeWithTag("comment-input").performTextInput("Privato")
        compose.onNodeWithTag("save-comment").performScrollTo().performClick()
        compose.waitUntil { repo.calls.size == 1 }
        compose.onNodeWithTag("save-comment").assertIsNotEnabled()
        compose.onNodeWithTag("cancel-comment").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { access.value = AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE) }
        compose.onNodeWithTag("connection-blocked").assertIsDisplayed()
        compose.onNodeWithTag("comment-dialog").assertDoesNotExist()
        pending.complete(Unit)
        compose.waitForIdle()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
        assertEquals(1, repo.calls.size)
        assertTrue(repo.closed > 0)
    }

    @Test fun revokedSessionBlocksWritesAndLogoutOrAccountSwitchDiscardDrafts() {
        show(); openDetail(); add()
        compose.onNodeWithTag("comment-input").performTextInput("Bozza privata")
        compose.runOnIdle { access.value = AccessState.SignedOut() }
        compose.onNodeWithTag("sign-in").assertIsDisplayed()
        compose.onNodeWithTag("comment-dialog").assertDoesNotExist()
        compose.runOnIdle { access.value = AccessState.Connected(account.copy(id = UUID(0, 98))) }
        openDetail(); add()
        compose.onNodeWithText("Bozza privata").assertDoesNotExist()
        repo.authProblem = AuthProblem.INVALID_CREDENTIALS
        compose.onNodeWithTag("comment-input").performTextInput("Nuovo")
        compose.onNodeWithTag("save-comment").performScrollTo().performClick()
        compose.onNodeWithTag("connection-blocked").assertIsDisplayed()
        compose.onNodeWithTag("comment-dialog").assertDoesNotExist()
    }

    @Test fun checkingDuringDispatchReconcilesAllPagesWhenTheSameAccountReturns() {
        val pending = CompletableDeferred<Unit>()
        repo.pending = pending
        show(); openDetail(); add()
        compose.onNodeWithTag("comment-input").performTextInput("Esito incerto al rientro")
        compose.onNodeWithTag("save-comment").performScrollTo().performClick()
        compose.waitUntil { repo.calls.size == 1 }
        compose.runOnIdle { access.value = AccessState.Checking }
        compose.onNodeWithTag("checking-session").assertIsDisplayed()
        pending.complete(Unit)
        compose.waitUntil { repo.rows.any { it.id == created } }
        compose.runOnIdle { access.value = AccessState.Connected(account) }
        compose.onNodeWithTag("play-detail").assertIsDisplayed()
        compose.onNodeWithTag("detail-content").performScrollToNode(hasText("Esito incerto al rientro"))
        compose.onNodeWithText("Esito incerto al rientro").assertIsDisplayed()
        assertTrue(repo.reads.any { it != null })
        assertFalse(repo.uncertain)
        assertEquals(1, repo.calls.size)
        compose.onNodeWithTag("comment-dialog").assertDoesNotExist()
    }

    private fun show() {
        compose.setContent { KingScoreTheme { AppContent(access.value, { _, _ -> }, {}, {}, {}, playsFactory = { repo },
            onAccessFailure = { access.value = AccessState.Blocked(it) }) } }
    }
    private fun openDetail() {
        compose.onNodeWithTag("dashboard-content").performScrollToNode(hasTestTag("recent-${play.play.id}"))
        compose.onNodeWithTag("recent-${play.play.id}").performClick()
        compose.onNodeWithTag("play-detail").assertIsDisplayed()
    }
    private fun add() {
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag("add-comment"))
        compose.onNodeWithTag("add-comment").performScrollTo().assertIsEnabled().performClick()
    }
    private fun save() {
        compose.onNodeWithTag("save-comment").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("comment-dialog").assertDoesNotExist()
    }
    private fun commentButton(tag: String) {
        compose.onNodeWithTag("detail-content").performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performClick()
    }
    private fun capture(name: String, dialog: Boolean = false) {
        compose.waitForIdle()
        val bitmap = (if (dialog) compose.onNodeWithTag("comment-dialog") else compose.onRoot()).captureToImage().asAndroidBitmap()
        File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "$name.png").outputStream().use {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }

    private class FakePlays : PlaysRepository {
        val rows = mutableListOf(own, other)
        val calls = mutableListOf<String>()
        var failAfterCommit = false
        var problem: CommentProblem? = null
        var authProblem: AuthProblem? = null
        var pending: CompletableDeferred<Unit>? = null
        var closed = 0
        var uncertain = false
        val reads = mutableListOf<CommentCursor?>()
        override val commentWrites = object : CommentWrites {
            override fun close() { closed++ }
            override fun hasUncertainOutcome(playId: UUID) = uncertain
            override fun acknowledgeOutcome(playId: UUID) { uncertain = false }
            private suspend fun before() {
                pending?.let { withContext(NonCancellable) { it.await() } }
                authProblem?.let { throw AuthFailure(it) }
                problem?.let { throw CommentFailure(it) }
            }
            override suspend fun create(command: CreateComment): PlayCommentRow {
                calls += "create:${command.content}"; uncertain = true; before()
                val row = own.copy(id = created, author_name = "Server", content = command.content)
                rows += row
                currentCoroutineContext().ensureActive()
                if (failAfterCommit) throw CommentFailure(CommentProblem.UNCERTAIN)
                uncertain = false
                return row
            }
            override suspend fun update(playId: UUID, commentId: UUID, command: UpdateComment): PlayCommentRow {
                calls += "update:${command.content}"; before()
                val row = rows.first { it.id == commentId }.copy(content = command.content, author_name = "Server")
                rows[rows.indexOfFirst { it.id == commentId }] = row; return row
            }
            override suspend fun delete(playId: UUID, commentId: UUID): PlayCommentRow {
                calls += "delete"; before(); val row = rows.first { it.id == commentId }; rows.remove(row); return row
            }
        }
        override suspend fun history(filters: HistoryFilters, after: PlayCursor?) = PlayPage(listOf(play), null)
        override suspend fun filterOptions() = HistoryOptions()
        override suspend fun detail(id: UUID) = play
        override suspend fun comments(id: UUID, after: CommentCursor?): CommentPage {
            reads += after
            return if (uncertain && after == null) CommentPage(listOf(own), CommentCursor(own.created_at, own.id))
                else if (after != null) CommentPage(rows.drop(1), null) else CommentPage(rows.toList(), null)
        }
        override suspend fun player(id: UUID): PlayerRow? = null
        override suspend fun game(id: UUID): GameRow? = null
    }
    companion object {
        private val account = Account(UUID(0, 99), "Marta", "test@example.com", true)
        private val play = PlayDetail(PlayRow(UUID(0, 1), null, null, LocalDate.parse("2026-10-13"), null, null, 45, "offline", "Fixture commenti", null, false, null),
            GameRow(UUID(0, 10), "Azul", null, null, 2, 4, null), emptyList())
        private val created = UUID(0, 40)
        private val own = PlayCommentRow(UUID(0, 11), play.play.id, account.id, "Marta", "Mio", Instant.parse("2026-10-13T12:00:00Z"), Instant.parse("2026-10-13T12:00:00Z"))
        private val other = own.copy(id = UUID(0, 12), user_id = UUID(0, 91), author_name = "Luca", content = "Altrui")
    }
}
