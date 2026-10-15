package app.kingscore.android

import org.junit.Assert.*
import org.junit.Test

class PlayEditorCapabilityTest {
    @Test fun `editor factory stays absent without configured authentication`() {
        assertNull(KingScoreApplication().playWritesFactory)
    }
}
