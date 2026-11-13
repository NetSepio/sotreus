package app.sotreus.edge.protocol.mesh

/**
 * The Meshtastic LoRa region for a country (ISO 3166-1 alpha-2), used to fill an unset region
 * from this phone's mobile network country. Only unambiguous cases; null means "ask the owner".
 */
object MeshRegionGuess {
    private val EU_868 = setOf(
        "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE", "GR", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL",
        "PL", "PT", "RO", "SK", "SI", "ES", "SE", "GB", "NO", "CH", "IS", "LI",
    )

    fun forCountry(iso: String?): String? {
        val c = iso?.trim()?.uppercase()?.takeIf { it.length == 2 } ?: return null
        return when (c) {
            in EU_868 -> "EU_868"
            "US", "CA", "MX" -> "US"
            "IN" -> "IN"
            "CN" -> "CN"
            "JP" -> "JP"
            "KR" -> "KR"
            "TW" -> "TW"
            "RU" -> "RU"
            "TH" -> "TH"
            "UA" -> "UA_868"
            "MY" -> "MY_919"
            "SG" -> "SG_923"
            "AU" -> "ANZ"
            "NZ" -> "NZ_865"
            "PH" -> "PH_915"
            "KZ" -> "KZ_863"
            "NP" -> "NP_865"
            "BR" -> "BR_902"
            else -> null
        }
    }
}
