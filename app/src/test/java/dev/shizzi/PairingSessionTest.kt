package dev.shizzi

import dev.shizzi.adb.PairingSession
import org.junit.Assert.*
import org.junit.Test

class PairingSessionTest {
    @Test fun `submission cannot wait for a future pairing window`() {
        val session = PairingSession(100)
        val old = session.revision
        assertNull(session.endpoint(old))
        session.discovered(32100)
        assertNull(session.endpoint(old))
        assertEquals(32100, session.endpoint(session.revision))
    }
    @Test fun `closing and reopening invalidates code even when port is reused`() {
        val session = PairingSession(100)
        session.discovered(32100)
        val old = session.revision
        session.discovered(0)
        assertNull(session.endpoint(old))
        session.discovered(32100)
        assertNull(session.endpoint(old))
        assertEquals(32100, session.endpoint(session.revision))
    }
    @Test fun `replacement endpoint rejects old notification reply`() {
        val session = PairingSession(100)
        session.discovered(32100)
        val old = session.revision
        session.discovered(32101)
        assertNull(session.endpoint(old))
        assertNull(session.endpoint(old, 32100))
        assertEquals(32101, session.endpoint(session.revision))
    }
    @Test fun `duplicate discovery preserves current code`() {
        val session = PairingSession(100)
        session.discovered(32100)
        val revision = session.revision
        session.discovered(32100)
        assertEquals(revision, session.revision)
        assertEquals(32100, session.endpoint(revision))
    }
    @Test fun `manual endpoint works when discovery is unavailable`() {
        val session = PairingSession(100)
        assertEquals(32100, session.endpoint(session.revision, 32100))
        assertNull(session.endpoint(session.revision, -1))
        assertNull(session.endpoint(session.revision, 65536))
        assertNull(session.endpoint(session.revision))
    }
}
