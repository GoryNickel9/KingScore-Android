package app.kingscore.android.core.network

import org.junit.Assert.*
import org.junit.Test

class ValidatedDefaultNetworkTest {
    @Test fun `new default remains blocked until its supplied capabilities validate Internet`() {
        val network = ValidatedDefaultNetwork()
        assertFalse(network.connected.value)
        network.available(1); assertFalse(network.connected.value)
        network.capabilities(1, true); assertTrue(network.connected.value)
        network.capabilities(1, false); assertFalse(network.connected.value)
    }
    @Test fun `loss blocks immediately and a stale validated callback cannot restore access`() {
        val network = ValidatedDefaultNetwork()
        network.available(1); network.capabilities(1, true)
        network.lost(1); assertFalse(network.connected.value)
        network.capabilities(1, true); assertFalse(network.connected.value)
    }
    @Test fun `callbacks from the old network cannot change the new default`() {
        val network = ValidatedDefaultNetwork()
        network.available(1); network.capabilities(1, true)
        network.available(2); assertFalse(network.connected.value)
        network.capabilities(1, true); assertFalse(network.connected.value)
        network.capabilities(2, true); network.lost(1); assertTrue(network.connected.value)
        network.capabilities(1, false); assertTrue(network.connected.value)
        network.lost(2); assertFalse(network.connected.value)
    }
    @Test fun `reconnection requires a new availability event and new validation`() {
        val network = ValidatedDefaultNetwork()
        network.available(1); network.capabilities(1, true); network.lost(1)
        network.capabilities(2, true); assertFalse(network.connected.value)
        network.available(2); assertFalse(network.connected.value)
        network.capabilities(2, true); assertTrue(network.connected.value)
    }
}
