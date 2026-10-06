package app.sotreus.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.res.stringResource
import app.sotreus.core.model.AttentionHeadline
import app.sotreus.core.model.AttentionReason
import app.sotreus.core.model.AttentionReasonKind
import app.sotreus.core.model.Confidence
import app.sotreus.core.model.DeviceFamily

@Composable
@ReadOnlyComposable
fun attentionHeadline(headline: AttentionHeadline, family: DeviceFamily?): String = when (headline) {
    AttentionHeadline.CROSS_LOCATION_REENCOUNTER -> stringResource(R.string.headline_cross_location)
    AttentionHeadline.FAMILY_SIGNATURE_OBSERVED -> family?.let { stringResource(R.string.headline_family, familySignature(it)) }
        ?: stringResource(R.string.headline_family_generic)
    AttentionHeadline.TAGGED_REENCOUNTERED -> stringResource(R.string.headline_tagged)
    AttentionHeadline.PERSISTENT_NEW_RADIO -> stringResource(R.string.headline_persistent_new)
}

/** Reason row text: bold title and a muted data detail (screen 05). */
@Composable
fun attentionReasonItem(r: AttentionReason): ReasonItem = when (r.kind) {
    AttentionReasonKind.TAGGED_BY_YOU -> ReasonItem(stringResource(R.string.reason_tagged), r.args.firstOrNull()?.let { "“$it”" }, r.strong)
    AttentionReasonKind.SEEN_AT_OTHER_PLACES -> ReasonItem(stringResource(R.string.reason_other_places), r.args.joinToString(", "), r.strong)
    AttentionReasonKind.REPEATED_THIS_SESSION -> ReasonItem(
        stringResource(R.string.reason_repeated),
        if (r.args.size == 2) stringResource(R.string.reason_repeated_detail, r.args[0], r.args[1]) else null,
        r.strong,
    )
    AttentionReasonKind.KNOWN_FAMILY -> {
        val family = r.args.getOrNull(0)?.let { n -> DeviceFamily.entries.firstOrNull { it.name == n } }
        val conf = r.args.getOrNull(1)?.let { n -> Confidence.entries.firstOrNull { it.name == n } }
        ReasonItem(
            stringResource(R.string.reason_family),
            listOfNotNull(family?.let { familyName(it) }, conf?.let { confidenceLabel(it) }).joinToString(" · ").ifBlank { null },
            r.strong,
        )
    }
    AttentionReasonKind.NEW_TO_PLACE -> ReasonItem(stringResource(R.string.reason_new_here), null, r.strong)
}
