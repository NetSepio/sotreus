package app.sotreus.feature.context

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sotreus.context.ContextLocation
import app.sotreus.core.model.Provenance
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.StateChip
import kotlin.math.roundToInt

@Composable
internal fun ProvenanceChip(p: Provenance) = StateChip(
    stringResource(
        when (p) {
            Provenance.SENSED -> R.string.ctx_prov_sensed
            Provenance.NETWORK -> R.string.ctx_prov_network
            Provenance.PREDICTED -> R.string.ctx_prov_predicted
        },
    ),
    if (p == Provenance.SENSED) ChipTone.ACCENT else ChipTone.NEUTRAL,
)

@Composable
internal fun locationSource(l: ContextLocation): String = when (l.source) {
    ContextLocation.Source.PLACE -> stringResource(R.string.ctx_loc_place, l.placeName.orEmpty())
    ContextLocation.Source.SESSION -> stringResource(R.string.ctx_loc_session)
    ContextLocation.Source.PHONE -> stringResource(R.string.ctx_loc_phone)
}

internal fun km(d: Double): String = if (d < 10) "%.1f km".format(d) else "${d.roundToInt()} km"

internal fun altitude(m: Double?): String? = m?.let { "%,d m".format(it.roundToInt()) }

internal fun speed(mps: Double?): String? = mps?.let { "${(it * 3.6).roundToInt()} km/h" }

internal fun degrees(d: Double?): String? = d?.let { "${it.roundToInt()}°" }

/** Eight-point compass name for a course or azimuth. */
internal fun compass(deg: Double): String {
    val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
    return names[(((deg % 360) + 360) % 360 / 45.0).roundToInt() % 8]
}
