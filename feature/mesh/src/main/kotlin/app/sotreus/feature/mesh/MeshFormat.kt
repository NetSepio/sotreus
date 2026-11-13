package app.sotreus.feature.mesh

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sotreus.core.data.mesh.MeshRows
import app.sotreus.core.database.entity.MeshNodeEntity
import app.sotreus.core.ui.ageShort
import app.sotreus.edge.protocol.mesh.MeshChannel
import app.sotreus.edge.protocol.mesh.MeshConfig
import app.sotreus.edge.protocol.mesh.MeshDelivery
import app.sotreus.edge.protocol.mesh.MeshGeo
import app.sotreus.edge.protocol.mesh.MeshNames
import app.sotreus.edge.protocol.mesh.MeshNodeId
import app.sotreus.edge.protocol.mesh.MeshPosition
import app.sotreus.edge.protocol.mesh.MeshPrecision
import app.sotreus.sensing.LocationSource

// Formatting shared by the mesh screens. Signal values are link quality of the last packet only;
// distance and bearing come from reported positions only (HANDOFF_V3_MESH.md §3.2).

internal fun MeshNodeEntity.label(): String = shortName ?: longName ?: MeshNodeId.format(num)
internal fun MeshNodeEntity.title(): String = longName ?: shortName ?: MeshNodeId.format(num)

internal fun MeshNodeEntity.position(): MeshPosition? {
    val la = lat ?: return null
    val lo = lon ?: return null
    return MeshPosition(la, lo, altM, precisionBits, positionMs?.let { it / 1000 })
}

/** The short tag shown in a node's square: its short name, else the last 4 hex digits. */
internal fun shortTag(node: MeshNodeEntity?, num: Long): String = node?.shortName ?: MeshNodeId.format(num).takeLast(4).uppercase()

internal fun channelName(config: MeshConfig?, index: Int): String =
    config?.channels?.getOrNull(index)?.displayName(index, config.preset) ?: if (index == 0) MeshNames.channelForPreset(config?.preset) else "Channel $index"

@Composable
internal fun keyLabel(ch: MeshChannel?): String = when {
    ch == null -> stringResource(R.string.mesh_key_default)
    ch.defaultKey -> stringResource(R.string.mesh_key_default)
    ch.noKey -> stringResource(R.string.mesh_key_none)
    else -> stringResource(R.string.mesh_key_private)
}

@Composable
internal fun hopsLabel(hops: Int?): String? = when {
    hops == null -> null
    hops == 0 -> stringResource(R.string.mesh_direct)
    else -> pluralStringResource(R.plurals.mesh_hops, hops, hops)
}

internal fun snr(v: Float?): String? = v?.let { "%.1f".format(java.util.Locale.ROOT, it).replace("-", "−") }

@Composable
internal fun deliveryLabel(d: String?, reason: Int? = null): String? = when (MeshDelivery.of(d)) {
    MeshDelivery.QUEUED -> stringResource(R.string.mesh_delivery_queued)
    MeshDelivery.SENT -> stringResource(R.string.mesh_delivery_sent)
    MeshDelivery.RELAYED -> stringResource(R.string.mesh_delivery_relayed)
    MeshDelivery.DELIVERED -> stringResource(R.string.mesh_delivery_delivered)
    MeshDelivery.NO_ACK -> stringResource(R.string.mesh_delivery_no_ack)
    MeshDelivery.NO_RELAY_HEARD -> stringResource(R.string.mesh_delivery_no_relay)
    MeshDelivery.FAILED -> if (reason != null) stringResource(R.string.mesh_delivery_failed_reason, routingError(reason)) else stringResource(R.string.mesh_delivery_failed)
    null -> null
}

internal fun deliveryFailed(d: String?): Boolean = MeshDelivery.of(d).let { it == MeshDelivery.FAILED || it == MeshDelivery.NO_ACK || it == MeshDelivery.NO_RELAY_HEARD }

/** Meshtastic's routing errors, as plain words. */
@Composable
internal fun routingError(reason: Int): String = stringResource(
    when (reason) {
        1 -> R.string.mesh_err_no_route
        2 -> R.string.mesh_err_got_nak
        3 -> R.string.mesh_err_timeout
        5 -> R.string.mesh_err_max_retransmit
        6 -> R.string.mesh_err_no_channel
        7 -> R.string.mesh_err_too_large
        9 -> R.string.mesh_err_duty_cycle
        32, 33, 34, 35 -> R.string.mesh_err_key
        else -> R.string.mesh_err_other
    },
)

/** "≈ 3 km NE", "≈ 440 m N", or "within ±2.9 km" when the node may be anywhere around this phone. */
@Composable
internal fun distanceLabel(b: MeshGeo.Between): String {
    val dist = metresLabel(b.metres)
    val compass = b.compass
    return if (compass == null) stringResource(R.string.mesh_within, metresLabel(b.uncertaintyM))
    else stringResource(R.string.mesh_distance, dist, compass.name)
}

internal fun metresLabel(m: Double): String = when {
    m >= 10_000 -> "%.0f km".format(java.util.Locale.ROOT, m / 1000)
    m >= 1_000 -> "%.1f km".format(java.util.Locale.ROOT, m / 1000).replace(".0 km", " km")
    else -> "%.0f m".format(java.util.Locale.ROOT, m)
}

@Composable
internal fun precisionLabel(bits: Int?): String {
    val m = MeshPrecision.metres(bits ?: MeshPrecision.EXACT) ?: return stringResource(R.string.mesh_precision_exact)
    return stringResource(R.string.mesh_precision, metresLabel(m), bits ?: MeshPrecision.EXACT)
}

internal fun between(phone: LocationSource.Fix?, node: MeshNodeEntity, nowMs: Long): MeshGeo.Between? =
    MeshGeo.between(phone?.lat, phone?.lon, phone?.accuracyM?.toDouble(), node.position(), node.lastHeardMs, nowMs)

internal fun isStale(node: MeshNodeEntity, nowMs: Long): Boolean = MeshGeo.isStale(node.position(), node.lastHeardMs, nowMs)

@Composable
internal fun ago(nowMs: Long, atMs: Long?): String = atMs?.let { ageShort((nowMs - it).coerceAtLeast(0)) } ?: stringResource(R.string.mesh_never)

internal fun conversationTitle(conversation: String, config: MeshConfig?, nodes: Map<Long, MeshNodeEntity>): String {
    MeshRows.channelOf(conversation)?.let { return channelName(config, it) }
    val peer = MeshRows.peerOf(conversation) ?: return conversation
    return nodes[peer]?.title() ?: MeshNodeId.format(peer)
}

/** The protocol's role and preset names, shown as Meshtastic shows them. */
internal fun roleLabel(role: String?): String = role?.let(MeshNames::title) ?: "Client"
