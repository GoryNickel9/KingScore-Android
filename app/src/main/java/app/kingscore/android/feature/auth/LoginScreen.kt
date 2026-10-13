package app.kingscore.android.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import app.kingscore.android.core.auth.AuthProblem

@Composable
fun LoginScreen(
    email: String,
    onEmailChange: (String) -> Unit,
    problem: AuthProblem?,
    onSignIn: (String, String) -> Unit,
) {
    // Password stays in memory only; never use rememberSaveable or SavedStateHandle.
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    val submit = {
        if (email.isNotBlank() && password.isNotEmpty()) {
            keyboard?.hide()
            val submitted = password
            password = ""
            visible = false
            onSignIn(email, submitted)
        }
    }
    Column(
        modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        Text("KingScore", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text("Le partite del tuo gruppo", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(12.dp))
        Text("Accedi con il tuo account KingScore.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(36.dp))
        OutlinedTextField(
            value = email, onValueChange = onEmailChange,
            label = { Text("Email") }, singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("email"),
        )
        Spacer(Modifier.height(16.dp))
        OutlinedTextField(
            value = password, onValueChange = { password = it },
            label = { Text("Password") }, singleLine = true,
            visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            trailingIcon = {
                IconButton(onClick = { visible = !visible }) {
                    Icon(if (visible) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
                        contentDescription = if (visible) "Nascondi password" else "Mostra password")
                }
            },
            modifier = Modifier.fillMaxWidth().testTag("password"),
        )
        if (problem != null) {
            Spacer(Modifier.height(12.dp))
            Text(problem.message(), color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("login-error"))
        }
        Spacer(Modifier.height(24.dp))
        Button(onClick = { submit() }, enabled = email.isNotBlank() && password.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().height(52.dp).testTag("sign-in")) { Text("Accedi") }
        Spacer(Modifier.height(20.dp))
        Text("Serve una connessione Internet per usare KingScore.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

fun AuthProblem.message(): String = when (this) {
    AuthProblem.INVALID_CREDENTIALS -> "Accesso non riuscito. Controlla email e password."
    AuthProblem.NETWORK_UNAVAILABLE -> "Connessione Internet non disponibile."
    AuthProblem.BACKEND_UNAVAILABLE -> "KingScore non è raggiungibile. Riprova."
    AuthProblem.LOCAL_STORAGE -> "Impossibile aggiornare la sessione sul dispositivo. Riprova."
    AuthProblem.UNEXPECTED_RESPONSE -> "Accesso temporaneamente non disponibile. Riprova."
}
