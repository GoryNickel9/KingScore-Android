package app.kingscore.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable

/** Test-only host: the release APK contains neither this Activity nor its content hook. */
class DebugRecreationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { content?.invoke() }
    }
    companion object { var content: (@Composable () -> Unit)? = null }
}
