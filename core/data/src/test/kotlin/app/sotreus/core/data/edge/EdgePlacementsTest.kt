package app.sotreus.core.data.edge

import app.sotreus.core.database.entity.EdgeDeviceEntity
import app.sotreus.core.model.EdgeDeployment
import app.sotreus.edge.protocol.DeploymentMode
import app.sotreus.edge.protocol.EdgePlacement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A placement saved on the phone while the Edge is away, and the check that the Edge kept it. */
class EdgePlacementsTest {
    private val stationary = EdgeDeviceEntity(
        deviceId = "9f3c00aa11bb22cc", name = "Sotreus Edge", suffix = "96FF", hardware = "heltec_wifi_lora_32_v3", firmware = "0.1.0",
        protocol = 1, capsJson = "{}", probeSalt = "0a0b", btAddress = "C4:5B:BE:96:FF:01", pairedAtMs = 1, syncCursor = 1,
        deployment = EdgeDeployment.STATIONARY, placeLat = 12.97160, placeLon = 77.59460, placeAccuracyM = 42f, placeSetMs = 5,
        placeSource = "phone", placementPending = true,
    )

    @Test fun theDeviceRowGivesThePlacementToSend() {
        val p = stationary.placement()
        assertEquals(DeploymentMode.STATIONARY, p.mode)
        assertEquals(12.97160, p.lat!!, 0.0)
        assertEquals(42f, p.accuracyM!!, 0f)
        assertEquals("phone", p.source)
        assertEquals(DeploymentMode.TRAVEL, stationary.copy(deployment = EdgeDeployment.TRAVEL).placement().mode)
    }

    @Test fun aPositionIsSentWholeAndAModeAlone() {
        val whole = placementJson(stationary.placement())["placement"]!!.jsonObject
        assertEquals("stationary", whole["mode"]!!.jsonPrimitive.content)
        assertTrue("lat" in whole)
        val modeOnly = placementJson(EdgePlacement(DeploymentMode.TRAVEL))["placement"] as JsonObject
        assertEquals(setOf("mode"), modeOnly.keys)
    }

    @Test fun theEdgeKeptItAllowingForItsRounding() {
        val sent = stationary.placement()
        // The Edge stores degrees × 10⁷ as integers.
        val rounded = sent.copy(lat = Math.round(sent.lat!! * 1e7) / 1e7 + 0.5e-7, lon = Math.round(sent.lon!! * 1e7) / 1e7)
        assertTrue(rounded.keeps(sent))
        assertFalse(sent.copy(mode = DeploymentMode.TRAVEL).keeps(sent))
        assertFalse(sent.copy(lat = sent.lat!! + 1e-5).keeps(sent)) // about a metre: a different position
        assertFalse(EdgePlacement(DeploymentMode.STATIONARY).keeps(sent)) // the position was dropped
        assertTrue(EdgePlacement(DeploymentMode.TRAVEL).keeps(EdgePlacement(DeploymentMode.TRAVEL)))
    }
}
