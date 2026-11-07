package app.sotreus.core.data.edge

import app.sotreus.core.database.entity.EdgeDeviceEntity
import app.sotreus.core.model.EdgeDeployment
import app.sotreus.edge.protocol.DeploymentMode
import app.sotreus.edge.protocol.EdgePlacement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs

/** The placement (mode and position) this phone holds for the Edge. */
internal fun EdgeDeviceEntity.placement(): EdgePlacement = EdgePlacement(
    mode = if (deployment == EdgeDeployment.STATIONARY) DeploymentMode.STATIONARY else DeploymentMode.TRAVEL,
    lat = placeLat, lon = placeLon, accuracyM = placeAccuracyM, setUnixMs = placeSetMs, source = placeSource,
)

/** `set_config` body. A mode alone keeps any position the Edge stores; a position is sent whole. */
internal fun placementJson(p: EdgePlacement): JsonObject {
    val body = if (p.positioned) p.toJson() else JsonObject(mapOf("mode" to JsonPrimitive(p.mode.wire)))
    return JsonObject(mapOf("placement" to body))
}

/** The Edge kept what was sent: the same mode and, when one was sent, the same position. */
internal fun EdgePlacement.keeps(sent: EdgePlacement): Boolean {
    if (mode != sent.mode) return false
    if (!sent.positioned) return true
    return positioned && abs(lat!! - sent.lat!!) < POSITION_EPSILON && abs(lon!! - sent.lon!!) < POSITION_EPSILON
}

/** The Edge stores degrees × 10⁷ as integers; allow its rounding (about 2 cm). */
private const val POSITION_EPSILON = 2e-7
