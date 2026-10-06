package app.sotreus.core.model

/**
 * The product flavor this build belongs to. Bound once by the flavor source set (`src/generic`,
 * `src/solanaMobile`); common code reads it from DI and never from `BuildConfig`.
 */
enum class Distribution(val flavorName: String) {
    GENERIC("generic"),
    SOLANA_MOBILE("solanaMobile"),
}
