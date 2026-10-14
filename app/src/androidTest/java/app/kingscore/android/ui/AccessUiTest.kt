package app.kingscore.android.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import app.kingscore.android.core.auth.Account
import app.kingscore.android.core.auth.AuthProblem
import app.kingscore.android.feature.auth.AccessState
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class AccessUiTest {
    @get:Rule val compose = createComposeRule()

    @Test fun loginExposesOnlyExistingAccountSignIn() {
        var submitted = ""
        var submittedPassword = ""
        compose.setContent {
            KingScoreTheme { AppContent(AccessState.SignedOut(), { email, password ->
                submitted = email
                submittedPassword = password
            }, {}, {}, {}) }
        }
        compose.onNodeWithTag("sign-in").assertIsNotEnabled()
        compose.onNodeWithTag("email").performTextInput("test@example.com")
        compose.onNodeWithTag("email").assertTextContains("test@example.com")
        compose.onNodeWithTag("password").performTextInput("test-password")
        // The IME can cover the button on the 320 dp PC emulator.
        compose.onNodeWithTag("sign-in").performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals("test@example.com", submitted)
            assertEquals("test-password", submittedPassword)
        }
        compose.onNodeWithTag("sign-in").assertIsNotEnabled()
        compose.onNodeWithText("Registrati").assertDoesNotExist()
        compose.onNodeWithText("Password dimenticata").assertDoesNotExist()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
    }

    @Test fun offlineStateContainsNoAuthenticatedNavigation() {
        compose.setContent {
            KingScoreTheme { AppContent(AccessState.Blocked(AuthProblem.NETWORK_UNAVAILABLE), { _, _ -> }, {}, {}, {}) }
        }
        compose.onNodeWithTag("connection-blocked").assertIsDisplayed()
        compose.onNodeWithTag("authenticated-shell").assertDoesNotExist()
    }

    @Test fun accountScreenShowsRoleAndLogoutWithoutForbiddenSettings() {
        val account = Account(UUID.fromString("00000000-0000-0000-0000-000000000001"), "Test", "test@example.com", true)
        compose.setContent {
            KingScoreTheme { AppContent(AccessState.Connected(account), { _, _ -> }, {}, {}, {}) }
        }
        compose.onNodeWithText("Profilo").performClick()
        compose.onNodeWithText("Amministratore").assertIsDisplayed()
        compose.onNodeWithTag("sign-out").assertIsDisplayed()
        compose.onNodeWithText("Cambia password").assertDoesNotExist()
        compose.onNodeWithText("Cambia email").assertDoesNotExist()
        compose.onNodeWithText("Ricalcola ELO").assertDoesNotExist()
    }
}
