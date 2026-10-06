package app.sotreus.feature.journey

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.sotreus.context.SessionContext
import app.sotreus.core.model.SatelliteGroup
import app.sotreus.core.ui.ChipTone
import app.sotreus.core.ui.InfoRow
import app.sotreus.core.ui.MonoLabel
import app.sotreus.core.ui.RowDivider
import app.sotreus.core.ui.StateChip
import app.sotreus.core.ui.clockTime
import kotlin.math.roundToInt

/** Context that overlapped the session in time. The wording never implies cause. */
@Composable
internal fun SessionContextSection(ctx: SessionContext) {
    val eo = ctx.satellitePasses.filter { it.elements.group == SatelliteGroup.EARTH_OBSERVATION }
    val otherSats = ctx.satellitePasses - eo.toSet()
    if (ctx.aircraft.isEmpty() && ctx.remoteId.isEmpty() && ctx.satellitePasses.isEmpty()) return
    MonoLabel(stringResource(R.string.summary_context))
    MonoLabel(stringResource(R.string.summary_context_note), small = true)
    Column {
        RowDivider(strong = true)
        if (ctx.remoteId.isNotEmpty()) {
            InfoRow(
                title = stringResource(R.string.summary_ctx_remote_id, ctx.remoteId.size),
                subtitle = ctx.remoteId.take(4).joinToString(" · ") { (it.title.ifBlank { it.subjectId }) + " " + clockTime(it.atMs) },
            ) { StateChip(stringResource(R.string.summary_prov_sensed), ChipTone.ACCENT) }
        }
        if (ctx.aircraft.isNotEmpty()) {
            val closest = ctx.aircraft.take(4).map { a -> a.title to a.distanceKm?.let { stringResource(R.string.summary_ctx_closest, it.roundToInt()) } }
            InfoRow(
                title = stringResource(R.string.summary_ctx_aircraft, ctx.aircraft.size),
                subtitle = closest.joinToString(" · ") { (t, d) -> if (d != null) "$t $d" else t },
            ) { StateChip(stringResource(R.string.summary_prov_network), ChipTone.NEUTRAL) }
        }
        if (eo.isNotEmpty()) {
            InfoRow(
                title = stringResource(R.string.summary_ctx_eo, eo.size),
                subtitle = eo.take(4).joinToString(" · ") { "${it.elements.name} ${clockTime(it.startMs)}" },
            ) { StateChip(stringResource(R.string.summary_prov_predicted), ChipTone.NEUTRAL) }
        }
        if (otherSats.isNotEmpty()) {
            InfoRow(
                title = stringResource(R.string.summary_ctx_other_sats, otherSats.size),
                subtitle = otherSats.take(4).joinToString(" · ") { "${it.elements.name} ${clockTime(it.startMs)}" },
            ) { StateChip(stringResource(R.string.summary_prov_predicted), ChipTone.NEUTRAL) }
        }
    }
}
