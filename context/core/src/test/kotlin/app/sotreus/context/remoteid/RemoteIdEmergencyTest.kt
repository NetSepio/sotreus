package app.sotreus.context.remoteid

import app.sotreus.intelligence.fieldwatch.OpenDroneId
import app.sotreus.intelligence.fieldwatch.PayloadLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIdEmergencyTest {
    @Test fun statusOrEmergencySelfIdDeclaresIt() {
        assertTrue(RemoteIdEmergency.declared(PayloadLocation(status = OpenDroneId.STATUS_EMERGENCY)))
        assertTrue(RemoteIdEmergency.declared(PayloadLocation(selfKind = OpenDroneId.SELF_EMERGENCY, selfId = "Low battery")))
        assertFalse(RemoteIdEmergency.declared(PayloadLocation(status = 2)))
        assertFalse(RemoteIdEmergency.declared(PayloadLocation(selfKind = 0, selfId = "Survey")))
        assertFalse(RemoteIdEmergency.declared(PayloadLocation(uasId = "1581F", basicId = true)))
    }

    @Test fun itKeepsItsStartWhileMessagesTakeTurns() {
        var e = RemoteIdEmergency.next(null, declared = true, atMs = 1_000)
        assertEquals(RemoteIdEmergency(1_000, 1_000), e)
        // A location message that says nothing about it, then the self-ID again.
        e = RemoteIdEmergency.next(e, declared = false, atMs = 2_000)
        assertEquals(1_000L, e!!.sinceMs)
        e = RemoteIdEmergency.next(e, declared = true, atMs = 5_000)
        assertEquals(RemoteIdEmergency(1_000, 5_000), e)
    }

    @Test fun itEndsOnceNothingSaysSo() {
        val e = RemoteIdEmergency.next(null, declared = true, atMs = 0)
        assertNull(RemoteIdEmergency.next(e, declared = false, atMs = RemoteIdEmergency.HOLD_MS + 1))
        // A later declaration is a new one.
        assertEquals(20_000L, RemoteIdEmergency.next(e, declared = true, atMs = 20_000)!!.sinceMs)
    }
}
