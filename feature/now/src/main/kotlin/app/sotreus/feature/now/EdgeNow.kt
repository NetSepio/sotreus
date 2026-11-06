package app.sotreus.feature.now

import android.animation.ValueAnimator
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import app.sotreus.core.data.edge.EdgeHub
import app.sotreus.core.data.edge.EdgeLive
import app.sotreus.core.data.live.LiveRadio
import app.sotreus.core.designsystem.theme.SotreusTheme
import app.sotreus.core.model.BleAddressType
import app.sotreus.core.ui.Freshness
import app.sotreus.core.ui.GhostButton
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.WarningBanner
import app.sotreus.core.ui.ageShort
import app.sotreus.core.ui.clockTime
import app.sotreus.core.ui.familyName
import app.sotreus.edge.link.EdgeLinkState
import app.sotreus.edge.location.EdgeLocationSource
import app.sotreus.edge.protocol.EdgeMode
import app.sotreus.intelligence.CategoryBasis
import app.sotreus.intelligence.CategoryGuess
import app.sotreus.intelligence.CategoryInput
import app.sotreus.intelligence.DeviceCategoryGuess

// V2: Now with a Sotreus Edge as the sensor (mocks 19 and 20).

/** "EDGE 96FF · UPDATED 2 S   WI-FI · 13 CHANNELS   BASELINE · 14 VISITS". */
@Composable
internal fun edgeFreshness(state: NowUiState, edge: EdgeLive): List<Freshness> {
    val snap = state.snapshot
    val suffix = edge.device?.suffix.orEmpty()
    val age = edge.lastLiveMs.takeIf { it > 0 }?.let { ageShort(snap.atMs - it) } ?: stringResource(R.string.fresh_none)
    val link = edge.status?.link
    val oneChannel = link != null && link.sniffing && !link.hopping
    return listOf(
        Freshness(stringResource(R.string.fresh_edge, suffix), stringResource(R.string.fresh_edge_updated, age), limited = !edge.connected),
        Freshness(stringResource(R.string.fresh_wifi), stringResource(if (oneChannel) R.string.fresh_edge_one_channel else R.string.fresh_edge_channels), limited = oneChannel),
        Freshness(
            if (snap.placeId == null || state.learning) stringResource(R.string.fresh_learning) else pluralStringResource(R.plurals.fresh_baseline, snap.baselineVisits, snap.baselineVisits),
            null,
        ),
    )
}

/** Where Edge sightings get their location right now. */
@Composable
internal fun EdgeTaggingLine(edge: EdgeLive, fixAccuracyM: Float?) {
    val text = when (edge.locationSource) {
        EdgeLocationSource.PHONE_WITH_EDGE -> when {
            !edge.connected || !edge.together -> stringResource(R.string.edge_tag_apart)
            fixAccuracyM != null -> stringResource(R.string.edge_tag_phone, "%.0f".format(fixAccuracyM))
            else -> stringResource(R.string.edge_tag_waiting)
        }
        EdgeLocationSource.EDGE_FIXED -> stringResource(R.string.edge_tag_fixed, edge.device?.placeAccuracyM?.let { "%.0f".format(it) } ?: "—")
        EdgeLocationSource.EDGE_GNSS -> stringResource(R.string.edge_tag_gnss)
        EdgeLocationSource.NONE -> stringResource(R.string.edge_tag_none)
    }
    MonoLabel(text, small = true)
}

/** Out of reach, connecting or in Mesh mode: say so, and offer the phone's radios (never switched silently). */
@Composable
internal fun EdgeLinkBanner(edge: EdgeLive, now: Long, onUsePhone: () -> Unit, onPairAgain: () -> Unit = {}) {
    val text = when {
        edge.pairingLost -> stringResource(R.string.edge_banner_pairing_lost)
        edge.status?.mode == EdgeMode.MESH -> stringResource(R.string.edge_banner_mesh)
        edge.link is EdgeLinkState.Lost -> stringResource(R.string.edge_banner_lost, clockTime((edge.link as EdgeLinkState.Lost).atMs))
        edge.link is EdgeLinkState.Failed -> stringResource(R.string.edge_banner_failed, (edge.link as EdgeLinkState.Failed).reason)
        edge.link is EdgeLinkState.Connected -> return
        else -> stringResource(R.string.edge_banner_connecting)
    }
    Column(verticalArrangement = Arrangement.spacedBy(SotreusTheme.spacing.s)) {
        WarningBanner(text)
        if (edge.pairingLost) app.sotreus.core.ui.PrimaryButton(stringResource(R.string.edge_pair_again), onPairAgain)
        GhostButton(stringResource(R.string.edge_use_phone), onUsePhone)
    }
}

@Composable
internal fun edgePill(edge: EdgeLive): String = stringResource(
    when {
        edge.status?.mode == EdgeMode.MESH -> R.string.edge_pill_mesh
        edge.connected -> R.string.edge_pill_live
        edge.pairingLost -> R.string.edge_pill_not_paired
        edge.link is EdgeLinkState.Lost || edge.link is EdgeLinkState.Failed -> R.string.edge_pill_out_of_reach
        else -> R.string.edge_pill_connecting
    },
)

/**
 * The sweep hand's angle, one turn per Edge update ([EdgeHub.LIVE_MS]). Null (no hand) when the
 * Edge is not connected or animations are off (reduce motion).
 */
@Composable
internal fun rememberSweepDegrees(running: Boolean): Float? {
    val animate = remember { ValueAnimator.areAnimatorsEnabled() }
    if (!running || !animate) return null
    val t = rememberInfiniteTransition(label = "sweep")
    val deg by t.animateFloat(0f, 360f, infiniteRepeatable(tween(EdgeHub.LIVE_MS.toInt(), easing = LinearEasing), RepeatMode.Restart), label = "hand")
    return deg
}

internal fun LiveRadio.category(): CategoryGuess = DeviceCategoryGuess.guess(
    CategoryInput(
        radio = kind, address = address,
        addressType = if (randomAddress) BleAddressType.RANDOM else BleAddressType.PUBLIC,
        wifiRole = wifiRole, family = family, signatureName = signatureName, guessConfidence = guessConfidence,
        vendor = vendor, hasRemoteId = hasRemoteId,
    ),
)

/** "Likely: Router · from vendor prefix (Ubiquiti)". Null when there is nothing to say. */
@Composable
internal fun categoryCaption(g: CategoryGuess): String? {
    val basis = when (g.basis) {
        CategoryBasis.SIGNATURE -> stringResource(R.string.category_basis_signature)
        CategoryBasis.ADVERTISEMENT -> stringResource(R.string.category_basis_advertisement)
        CategoryBasis.NETWORK -> stringResource(R.string.category_basis_network)
        CategoryBasis.VENDOR_PREFIX -> stringResource(R.string.category_basis_vendor, g.vendor.orEmpty())
        CategoryBasis.PRIVATE_ADDRESS -> return stringResource(R.string.category_private)
        CategoryBasis.ASKING_FOR_NETWORKS -> return stringResource(R.string.category_asking)
        CategoryBasis.NOTHING -> return null
    }
    val family = g.family ?: return basis.replaceFirstChar { it.uppercase() }
    return stringResource(R.string.category_likely, familyName(family), basis)
}

@Composable
internal fun categoryGroupName(g: CategoryGuess): String = when {
    g.family != null -> familyName(g.family!!)
    g.basis == CategoryBasis.ASKING_FOR_NETWORKS -> stringResource(R.string.category_group_asking)
    else -> stringResource(R.string.category_group_none)
}
