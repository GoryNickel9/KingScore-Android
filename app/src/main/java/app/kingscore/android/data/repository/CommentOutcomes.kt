package app.kingscore.android.data.repository

import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Only uncertain play IDs, never comment text, commands or a retry queue. */
class CommentOutcomes {
    private val pending = ConcurrentHashMap.newKeySet<UUID>()
    fun begin(playId: UUID) { if (!pending.add(playId)) throw CommentFailure(CommentProblem.UNCERTAIN) }
    fun has(playId: UUID): Boolean = playId in pending
    fun acknowledge(playId: UUID) { pending.remove(playId) }
    fun clear() { pending.clear() }
}

class AccountCommentOutcomes {
    private var account: UUID? = null
    private var outcomes = CommentOutcomes()
    fun forAccount(id: UUID): CommentOutcomes {
        if (account != id) { clear(); account = id }
        return outcomes
    }
    fun clear() { outcomes.clear(); outcomes = CommentOutcomes(); account = null }
}
