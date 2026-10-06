package app.sotreus.intelligence

import app.sotreus.core.model.AttentionDefaults
import app.sotreus.core.model.AttentionHeadline
import app.sotreus.core.model.AttentionInputs
import app.sotreus.core.model.AttentionReason
import app.sotreus.core.model.AttentionReasonKind
import app.sotreus.core.model.Confidence
import app.sotreus.core.model.DeviceFamily
import app.sotreus.core.model.UserEntityState
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * The attention engine (architecture handoff §11). It ranks what may be worth a look and always
 * says why. It is not a threat engine and the score is not a risk score.
 *
 * Inputs combine as a noisy-OR: each input can raise the score on its own, a tag alone cannot
 * cross the threshold, and stale data lowers everything.
 */
object AttentionEngine {
    private const val W_TAG = 0.50
    private const val W_REENCOUNTER = 0.45
    private const val W_PERSISTENCE = 0.20
    private const val W_NOVELTY = 0.25
    private const val W_KNOWN = 0.40
    private const val MIN_WINDOW_MINUTES = 5.0

    data class Context(
        val userState: UserEntityState,
        val family: DeviceFamily?,
        val familyConfidence: Confidence,
        val notableSignature: Boolean,
        val signatureName: String?,
        /** Names of other places where this entity was encountered before. */
        val otherPlaces: List<String>,
        val presentMinutes: Double,
        val windowMinutes: Double,
        val newToPlace: Boolean,
        val lastHeardAgeSeconds: Double,
        val staleAfterSeconds: Double,
        val displayName: String,
    )

    data class Assessment(
        val score: Float,
        val inputs: AttentionInputs,
        val reasons: List<AttentionReason>,
        val headline: AttentionHeadline,
    ) {
        val needsAttention: Boolean get() = score >= AttentionDefaults.NEEDS_ATTENTION_THRESHOLD
    }

    fun assess(c: Context): Assessment? {
        if (c.userState == UserEntityState.MINE || c.userState == UserEntityState.EXPECTED ||
            c.userState == UserEntityState.IGNORE
        ) return null

        val tag = if (c.userState == UserEntityState.TAGGED || c.userState == UserEntityState.WATCH) 1.0 else 0.0
        val reEncounter = 1 - exp(-c.otherPlaces.size.toDouble())
        val persistence = (c.presentMinutes / maxOf(c.windowMinutes, MIN_WINDOW_MINUTES)).coerceIn(0.0, 1.0)
        val novelty = if (c.newToPlace) 1.0 else 0.12
        val confidenceWeight = when (c.familyConfidence) {
            Confidence.HIGH -> 1.0
            Confidence.MEDIUM -> 0.7
            Confidence.LOW -> 0.4
        }
        val known = when {
            c.family == null -> 0.0
            c.notableSignature -> 1.0 * confidenceWeight
            c.family.attentionRelevant -> 0.6 * confidenceWeight
            else -> 0.3 * confidenceWeight
        }
        val freshness = (1 - c.lastHeardAgeSeconds / (c.staleAfterSeconds * 2)).coerceIn(0.0, 1.0)

        val miss = (1 - W_TAG * tag) * (1 - W_REENCOUNTER * reEncounter) * (1 - W_PERSISTENCE * persistence) *
            (1 - W_NOVELTY * novelty) * (1 - W_KNOWN * known)
        val score = ((1 - miss) * (0.5 + 0.5 * freshness)).coerceIn(0.0, 1.0)

        val reasons = buildList {
            if (tag > 0) add(AttentionReason(AttentionReasonKind.TAGGED_BY_YOU, listOf(c.displayName)))
            if (c.otherPlaces.isNotEmpty()) {
                add(AttentionReason(AttentionReasonKind.SEEN_AT_OTHER_PLACES, c.otherPlaces.take(3)))
            }
            if (persistence >= 0.5) {
                add(
                    AttentionReason(
                        AttentionReasonKind.REPEATED_THIS_SESSION,
                        listOf(c.presentMinutes.roundToInt().toString(), c.windowMinutes.roundToInt().toString()),
                    ),
                )
            }
            if (c.newToPlace && tag == 0.0) add(AttentionReason(AttentionReasonKind.NEW_TO_PLACE))
            if (c.family != null) {
                add(
                    AttentionReason(
                        AttentionReasonKind.KNOWN_FAMILY,
                        listOfNotNull(c.family.name, c.familyConfidence.name, c.signatureName),
                        strong = c.notableSignature || c.family.attentionRelevant,
                    ),
                )
            }
        }
        if (reasons.isEmpty()) return null

        val headline = when {
            c.otherPlaces.isNotEmpty() -> AttentionHeadline.CROSS_LOCATION_REENCOUNTER
            tag > 0 -> AttentionHeadline.TAGGED_REENCOUNTERED
            known > 0 && (c.notableSignature || c.family?.attentionRelevant == true) -> AttentionHeadline.FAMILY_SIGNATURE_OBSERVED
            else -> AttentionHeadline.PERSISTENT_NEW_RADIO
        }
        return Assessment(
            score = score.toFloat(),
            inputs = AttentionInputs(
                reEncounter = reEncounter.toFloat(),
                yourTag = tag.toFloat(),
                persistence = persistence.toFloat(),
                novelty = novelty.toFloat(),
                knownSignature = known.toFloat(),
                freshness = freshness.toFloat(),
            ),
            reasons = reasons,
            headline = headline,
        )
    }
}
