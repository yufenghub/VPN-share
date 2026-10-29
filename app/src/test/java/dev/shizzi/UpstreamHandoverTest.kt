package dev.shizzi

import org.junit.Assert.*
import org.junit.Test

class UpstreamHandoverTest {
    @Test fun `restart precedes verification even if the old upstream looks ready`() {
        val events = mutableListOf<String>()
        assertTrue(restartAndConfirmTetheringUpstream(
            restartHotspot = { events += "restart" },
            checkSelected = { events += "verify"; true },
        ))
        assertEquals(listOf("restart", "verify"), events)
    }

    @Test fun `a failed verification does not trigger another restart`() {
        var checks = 0
        var restarts = 0
        assertFalse(restartAndConfirmTetheringUpstream({ restarts++ }, { checks++; false }))
        assertEquals(1, restarts)
        assertEquals(1, checks)
    }

    @Test fun `a failed restart cannot be reported as a successful handover`() {
        assertThrows(IllegalStateException::class.java) {
            restartAndConfirmTetheringUpstream({ error("restart failed") }, { fail("verified after failed restart"); true })
        }
    }

    @Test fun `user closing the hotspot during verification does not restart it again`() {
        var restarts = 0
        assertThrows(HotspotClosedException::class.java) {
            restartAndConfirmTetheringUpstream({ restarts++ }, { throw HotspotClosedException() })
        }
        assertEquals(1, restarts)
    }

    @Test fun `a different system reported network cannot be filtered into an absent reading`() {
        val observed = UpstreamObservation("callback", listOf("network:123"), false, authoritative = true)
        val names = observed.liveInterfaceNames("testtun0")
        assertEquals(UpstreamReading.DRIFTED, classifyUpstream(names, "testtun0", observed.didTimeout))
    }
}
