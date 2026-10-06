package app.sotreus.social.presence

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Privacy test (handoff §31): the advertisement contains only the rotating token. */
class PresenceFrameTest {
    @Test
    fun frameIsMagicVersionAndTokenOnly() {
        val token = ByteArray(16) { it.toByte() }
        val frame = PresenceFrame.encode(token)
        assertEquals(18, frame.size)
        assertArrayEquals(token, PresenceFrame.decode(PresenceFrame.COMPANY_ID, frame))
    }

    @Test
    fun otherManufacturerDataIsIgnored() {
        assertNull(PresenceFrame.decode(0x004C, PresenceFrame.encode(ByteArray(16))))
        assertNull(PresenceFrame.decode(PresenceFrame.COMPANY_ID, ByteArray(10)))
    }
}
