package app.sotreus.core.model

/** What an attention event is about. Rendered from strings; never a threat verdict. */
enum class AttentionHeadline {
    CROSS_LOCATION_REENCOUNTER,
    FAMILY_SIGNATURE_OBSERVED,
    TAGGED_REENCOUNTERED,
    PERSISTENT_NEW_RADIO,
}

enum class AttentionReasonKind {
    TAGGED_BY_YOU,
    SEEN_AT_OTHER_PLACES,
    REPEATED_THIS_SESSION,
    KNOWN_FAMILY,
    NEW_TO_PLACE,
}

/**
 * One human-readable reason. [args] are data (place names, minutes, family), formatted by the UI.
 * [strong] reasons are drawn with the amber dot; supporting ones with the familiar glyph.
 */
data class AttentionReason(
    val kind: AttentionReasonKind,
    val args: List<String> = emptyList(),
    val strong: Boolean = true,
)

/** The attention engine's inputs, each 0..1, shown as bars on screen 05. */
data class AttentionInputs(
    val reEncounter: Float,
    val yourTag: Float,
    val persistence: Float,
    val novelty: Float,
    val knownSignature: Float,
    val freshness: Float,
)

object AttentionDefaults {
    /** Score at or above this is shown as "Needs attention" (HANDOFF_V1_UI.md §13: start at 0.60). */
    const val NEEDS_ATTENTION_THRESHOLD = 0.60f
}
