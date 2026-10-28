package dev.shizzi

import org.junit.Assert.*
import org.junit.Test

class UpstreamHandoverTest {
    @Test fun `a working upstream never restarts the hotspot`() {
        assertTrue(confirmTetheringUpstream({ true }, { fail("unnecessary hotspot restart") }))
    }

    @Test fun `a stale upstream can recover once`() {
        var checks = 0
        var restarts = 0
        assertTrue(confirmTetheringUpstream({ ++checks == 2 }, { restarts++ }))
        assertEquals(1, restarts)
    }

    @Test fun `a failed recovery cannot loop or claim success`() {
        var restarts = 0
        assertFalse(confirmTetheringUpstream({ false }, { restarts++ }))
        assertEquals(1, restarts)
    }

    @Test fun `user closing the hotspot aborts without reopening it`() {
        assertThrows(HotspotClosedException::class.java) {
            confirmTetheringUpstream({ throw HotspotClosedException() }, { fail("reopened after user stopped") })
        }
    }

    @Test fun `a different system reported network cannot be filtered into an absent reading`() {
        val observed = UpstreamObservation("callback", listOf("network:123"), false, authoritative = true)
        val names = observed.liveInterfaceNames("testtun0")
        assertEquals(UpstreamReading.DRIFTED, classifyUpstream(names, "testtun0", observed.didTimeout))
    }
}
