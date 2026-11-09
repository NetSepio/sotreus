package app.sotreus.core.data.edge

import app.sotreus.core.database.entity.EdgeDeviceEntity
import org.junit.Assert.assertEquals
import org.junit.Test

/** What "last from the Edge" shows after Sotreus restarts: never newer than what actually arrived. */
class EdgeLiveTest {
    private val device = EdgeDeviceEntity(
        deviceId = "9f3c00aa11bb22cc", name = "Sotreus Edge", suffix = "96FF", hardware = "board", firmware = "0.2.0",
        protocol = 1, capsJson = "{}", probeSalt = "0a0b", btAddress = "C4:5B:BE:96:FF:01", pairedAtMs = 0,
        lastConnectedMs = 9_000, lastSyncMs = 5_000, syncCursor = 1,
    )

    @Test fun afterARestartTheLastStoredRecordCounts() =
        assertEquals(5_000L, EdgeLive(device = device).lastDataMs)

    @Test fun somethingHeardThisRunIsNewer() =
        assertEquals(7_000L, EdgeLive(device = device, lastHeardMs = 7_000).lastDataMs)

    @Test fun connectingAloneIsNotData() =
        assertEquals(0L, EdgeLive(device = device.copy(lastSyncMs = null)).lastDataMs)

    @Test fun noDeviceNothingArrived() = assertEquals(0L, EdgeLive().lastDataMs)
}
