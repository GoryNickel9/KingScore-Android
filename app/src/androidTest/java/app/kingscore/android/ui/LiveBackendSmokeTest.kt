package app.kingscore.android.ui

import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.kingscore.android.MainActivity
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.feature.auth.message
import java.util.UUID
import org.json.JSONObject
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement

/** Explicit live-only smoke against MainActivity; no production dependency replacement or SDK shortcuts. */
@RunWith(AndroidJUnit4::class)
class LiveBackendSmokeTest {
    private val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var config: Config
    private val optIn = TestRule { base, _ -> object : Statement() {
        override fun evaluate() {
            val arguments = InstrumentationRegistry.getArguments()
            Assume.assumeTrue("Explicit live backend smoke opt-in required", arguments.getString("liveBackendSmoke") == "true")
            config = guarded("private configuration") { readConfig(arguments) }
            base.evaluate()
        }
    } }
    // The opt-in runs before the Activity rule, so ordinary fixture suites never launch the real app here.
    @get:Rule val rules: TestRule = RuleChain.outerRule(optIn).around(compose)

    @Test fun realLoginGlobalHistoryAdminUpdateAndOwnComments() {
        login(config.adminEmail, config.adminPassword)
        stage("admin_authenticated")
        navigate("ELO")
        waitFor("global standings shell") { exists(hasTestTag("ratings-content")) }
        waitFor("temporary player's published global standing", 120_000) {
            scrollIfPresent("ratings-content", hasTestTag("rating-${config.playerId}"))
        }
        tapTag("rating-${config.playerId}")
        waitFor("published history") {
            exists(hasTestTag("rating-history")) && exists(SemanticsMatcher("published history value") { node ->
                node.config.getOrNull(SemanticsProperties.Text)?.any { Regex("^-?[0-9]+ · [+-]?[0-9]+$").matches(it.text) } == true
            })
        }
        tapText("Chiudi")
        stage("global_standings_and_history")

        openTemporaryPlay()
        waitFor("admin management control") { exists(hasTestTag("manage-play")) }
        scrollTo("detail-content", hasTestTag("manage-play"))
        tapTag("manage-play")
        waitFor("real editor snapshot") { exists(hasTestTag("editor-game")) && exists(hasTestTag("editor-notes")) }
        scrollTo("editor-content", hasTestTag("editor-notes"))
        guarded("temporary play notes input") { compose.onNodeWithTag("editor-notes").performTextReplacement(config.notesMarker) }
        scrollTo("editor-content", hasTestTag("editor-save"))
        tapTag("editor-save") // One submission only; uncertainty is never automatically replayed by this test.
        waitFor("atomic update result") {
            scrollIfPresent("editor-content", hasTestTag("editor-success")) ||
                scrollIfPresent("editor-content", hasTestTag("editor-error"))
        }
        requireStep(exists(hasTestTag("editor-success")), "atomic update not confirmed")
        tapTag("editor-done")
        waitFor("updated temporary play detail") { scrollIfPresent("detail-content", hasText(config.notesMarker)) }
        stage("admin_atomic_update_confirmed")
        logout()

        if (!config.adminOnly) {
            login(config.userEmail, config.userPassword)
            navigate("Profilo")
            waitFor("ordinary account role") { exists(hasTestTag("account-role") and hasText("Giocatore")) }
            openTemporaryPlay(ordinary = true)
            requireStep(!exists(hasTestTag("manage-play")), "ordinary account received admin editor control")
            stage("ordinary_account_has_no_editor")
            ownComments()
            if (config.exerciseNetwork) networkRoundTrip()
            logout()
        }
        stage(if (config.adminOnly) "PASS_ADMIN_ONLY" else "PASS_FULL")
    }

    private fun login(email: String, password: String) {
        if (exists(hasTestTag("authenticated-shell"))) logout()
        waitFor("login form") { exists(hasTestTag("sign-in")) }
        // All credential input failures are replaced with a static error, without a semantics dump/cause.
        guarded("private credential input") {
            compose.onNodeWithTag("email").performTextReplacement(email)
            compose.onNodeWithTag("password").performTextReplacement(password)
            compose.onNodeWithTag("password").performImeAction()
        }
        stage("login_submitted")
        waitFor("verified authentication") {
            exists(hasTestTag("authenticated-shell")) || exists(hasTestTag("login-error")) ||
                exists(hasTestTag("connection-blocked"))
        }
        if (!exists(hasTestTag("authenticated-shell"))) {
            val problem = AuthProblem.entries.firstOrNull { exists(hasText(it.message())) }
            stage("login_rejected_${problem?.name ?: "unknown"}")
            requireStep(false, "authentication rejected")
        }
    }
    private fun logout() {
        navigate("Profilo")
        waitFor("logout control") { exists(hasTestTag("sign-out")) }
        guarded("logout") { compose.onNodeWithTag("sign-out").performScrollTo().performClick() }
        waitFor("signed out") { exists(hasTestTag("sign-in")) }
    }
    private fun navigate(label: String) = guarded("bottom navigation") {
        compose.onNode(hasText(label) and hasClickAction()).performClick()
    }
    private fun openTemporaryPlay(ordinary: Boolean = false) {
        navigate("Partite")
        waitFor("history search") { exists(hasTestTag("history-query")) }
        if (ordinary) {
            requireStep(!exists(hasTestTag("create-play")), "ordinary account received create control")
        }
        scrollTo("history-content", hasTestTag("history-query"))
        guarded("temporary game search") { compose.onNodeWithTag("history-query").performTextReplacement(config.gameName) }
        guarded("submit history search through keyboard") { compose.onNodeWithTag("history-query").performImeAction() }
        stage("history_search_submitted")
        waitFor("temporary play in searched history") { scrollIfPresent("history-content", hasTestTag("play-${config.playId}")) }
        tapTag("play-${config.playId}")
        waitFor("temporary game detail") { exists(hasTestTag("detail-content")) && exists(hasText(config.gameName)) }
        stage("temporary_play_opened")
    }
    private fun ownComments() {
        scrollTo("detail-content", hasTestTag("add-comment")); tapTag("add-comment")
        waitFor("new own comment dialog") { exists(hasTestTag("comment-input")) }
        guarded("own comment input") { compose.onNodeWithTag("comment-input").performTextReplacement(config.commentMarker) }
        saveComment()
        waitFor("created own comment") { scrollIfPresent("detail-content", hasText(config.commentMarker)) }
        val editTag = guarded("identify temporary own comment") {
            compose.onAllNodes(tagPrefix("edit-comment-")).fetchSemanticsNodes().singleOrNull()
                ?.config?.getOrNull(SemanticsProperties.TestTag) ?: throw AssertionError("Temporary account must have exactly one own comment")
        }
        val commentId = guarded("temporary comment identifier") { UUID.fromString(editTag.removePrefix("edit-comment-")) }
        scrollTo("detail-content", hasTestTag(editTag)); tapTag(editTag)
        val updated = "${config.commentMarker} updated"
        waitFor("edit own comment dialog") { exists(hasTestTag("comment-input")) }
        guarded("own comment update input") { compose.onNodeWithTag("comment-input").performTextReplacement(updated) }
        saveComment()
        waitFor("updated own comment") { scrollIfPresent("detail-content", hasText(updated)) }
        scrollTo("detail-content", hasTestTag("delete-comment-$commentId")); tapTag("delete-comment-$commentId")
        waitFor("own comment deletion confirmation") { exists(hasTestTag("confirm-delete-comment")) }
        guarded("confirm temporary own comment deletion") { compose.onNodeWithTag("confirm-delete-comment").performScrollTo().performClick() }
        waitFor("own comment deletion result") { !exists(hasTestTag("comment-dialog")) }
        waitFor("own comment deleted notice") { scrollIfPresent("detail-content", hasText("Commento eliminato.")) }
        requireStep(!exists(hasText(updated)), "deleted temporary comment remains visible")
        stage("ordinary_own_comment_create_update_delete")
    }
    private fun saveComment() {
        guarded("save own comment once") { compose.onNodeWithTag("save-comment").performScrollTo().performClick() }
        waitFor("own comment write confirmation") { !exists(hasTestTag("comment-dialog")) }
    }
    private fun networkRoundTrip() {
        val wifi = shell("settings get global wifi_on").trim()
        val data = shell("settings get global mobile_data").trim()
        requireStep(wifi in setOf("0", "1") && data in setOf("0", "1"), "device radios are transitioning")
        try {
            shell("svc wifi disable"); shell("svc data disable")
            waitFor("offline blocking") { exists(hasTestTag("connection-blocked")) }
            requireStep(!exists(hasTestTag("authenticated-shell")), "authenticated content visible without Internet")
            stage("offline_access_blocked")
        } finally {
            try { shell(if (wifi == "1") "svc wifi enable" else "svc wifi disable") }
            finally { shell(if (data == "1") "svc data enable" else "svc data disable") }
        }
        waitFor("live network and account restoration", 60_000) { exists(hasTestTag("authenticated-shell")) }
        stage("online_account_restored")
    }
    private fun shell(command: String): String = guarded("test-device shell operation") {
        val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.readBytes().decodeToString() }
    }
    private fun exists(matcher: SemanticsMatcher) = runCatching {
        compose.onAllNodes(matcher).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }.getOrDefault(false)
    private fun tagPrefix(prefix: String) = SemanticsMatcher("owned comment control") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true
    }
    private fun scrollIfPresent(container: String, matcher: SemanticsMatcher): Boolean = runCatching {
        compose.onNodeWithTag(container).performScrollToNode(matcher)
        exists(matcher)
    }.getOrDefault(false)
    private fun scrollTo(container: String, matcher: SemanticsMatcher) = guarded("scroll to required control") {
        compose.onNodeWithTag(container).performScrollToNode(matcher)
    }
    private fun tapTag(tag: String) = guarded("activate required control") { compose.onNodeWithTag(tag).performClick() }
    private fun tapText(text: String) = guarded("activate labelled control") {
        compose.onNode(hasText(text) and hasClickAction()).performClick()
    }
    private fun waitFor(label: String, timeout: Long = 45_000, condition: () -> Boolean) = guarded(label) {
        compose.waitUntil(timeoutMillis = timeout, condition = condition)
    }
    private fun requireStep(condition: Boolean, label: String) { if (!condition) throw AssertionError("Live smoke: $label") }
    private inline fun <T> guarded(label: String, action: () -> T): T = try { action() }
        catch (_: Throwable) { throw AssertionError("Live smoke step failed: $label") }
    private fun stage(name: String) = InstrumentationRegistry.getInstrumentation().sendStatus(2,
        Bundle().apply { putString("liveSmokeStage", name) })

    private class Config(val adminEmail: String, val adminPassword: String, val userEmail: String, val userPassword: String,
        val gameName: String, val playId: UUID, val playerId: UUID, val notesMarker: String, val commentMarker: String,
        val adminOnly: Boolean, val exerciseNetwork: Boolean)
    private fun readConfig(arguments: Bundle): Config {
        val path = arguments.getString("liveConfigFile")
        val json = path?.let {
            require(it.matches(Regex("^/data/local/tmp/kingscore-live-[A-Za-z0-9_-]+\\.json$")))
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $it")
            val bytes = ByteArray(16_385)
            val count = ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                var used = 0
                while (used < bytes.size) { val read = stream.read(bytes, used, bytes.size - used); if (read < 0) break; used += read }
                used
            }
            require(count in 1..16_384)
            JSONObject(String(bytes, 0, count, Charsets.UTF_8))
        }
        fun value(key: String): String {
            val value = if (json?.has(key) == true) json.opt(key) else arguments.getString(key)
            return when (value) {
                null, JSONObject.NULL -> ""
                is String -> value
                is Boolean -> value.toString()
                else -> throw IllegalArgumentException("Invalid live smoke configuration type")
            }
        }
        fun required(key: String, max: Int = 512) = value(key).also { require(it.isNotBlank() && it.length <= max) }
        val adminOnly = value("adminOnly") == "true"
        val game = required("tempGameName", 120).also { require(it.none(Char::isISOControl)) }
        return Config(required("adminEmail"), required("adminPassword"), if (adminOnly) "" else required("userEmail"),
            if (adminOnly) "" else required("userPassword"), game, UUID.fromString(required("tempPlayId")),
            UUID.fromString(required("tempPlayerId")), required("notesMarker", 200), required("commentMarker", 200),
            adminOnly, value("exerciseNetwork") == "true")
    }
}
