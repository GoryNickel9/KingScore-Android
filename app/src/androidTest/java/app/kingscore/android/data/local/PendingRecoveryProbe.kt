package app.kingscore.android.data.local

import androidx.test.runner.AndroidJUnitRunner
import android.app.Activity
import android.os.Bundle
import android.os.Process
import app.kingscore.android.data.repository.*
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** Test-APK-only probe, invoked twice with force-stop between write and read. */
class PendingRecoveryProbe : AndroidJUnitRunner() {
    private var mode: String? = null
    override fun onCreate(arguments: Bundle?) { mode = arguments?.getString("mode"); super.onCreate(arguments) }
    override fun onStart() {
        if (mode == null) { super.onStart(); return }
        val result = Bundle()
        try {
            runBlocking {
                val account = UUID(7, 44)
                val store = EncryptedPendingPlayStore(targetContext)
                val record = StoredPlayMutation(accountId = account.toString(), requestId = UUID(7, 45).toString(), playId = null,
                    kind = PlayMutationKind.CREATE, body = "{\"synthetic\":\"process-recovery-probe\"}", confirmedResponse = "{\"synthetic\":true}")
                when (mode) {
                    "write" -> store.save(account, record)
                    "read" -> { check(store.load(account) == record); store.save(account, null) }
                    else -> error("Probe mode required")
                }
            }
            result.putString("pendingProbe", "PASS"); result.putInt("processId", Process.myPid())
            finish(Activity.RESULT_OK, result)
        } catch (_: Exception) { result.putString("pendingProbe", "FAIL"); finish(Activity.RESULT_CANCELED, result) }
    }
}
