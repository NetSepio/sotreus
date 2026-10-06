package app.sotreus.core.model

/**
 * Where a piece of information came from. V1 only produces [SENSED]; [NETWORK] and [PREDICTED]
 * exist so the model stays open for V1.1 context sources.
 */
enum class Provenance {
    SENSED,
    NETWORK,
    PREDICTED,
}
