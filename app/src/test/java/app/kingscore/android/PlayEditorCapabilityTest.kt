package app.kingscore.android

import org.junit.Assert.*
import org.junit.Test

class PlayEditorCapabilityTest {
    @Test fun `production editor factory is absent before backend activation`() {
        assertFalse(ATOMIC_PLAY_EDITOR_ENABLED)
        assertNull(KingScoreApplication().playWritesFactory)
    }
}
