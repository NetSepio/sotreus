package app.sotreus.core.model

/**
 * Where a piece of information came from: [SENSED] by this phone's radios (radios, Remote ID),
 * reported by a [NETWORK] provider (aircraft), or [PREDICTED] by on-device computation (satellites).
 */
enum class Provenance {
    SENSED,
    NETWORK,
    PREDICTED,
}
