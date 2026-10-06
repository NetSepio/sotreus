/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit e71b9b2).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed.
 */
package app.sotreus.intelligence.fieldwatch

object OuiLookup {
    fun vendor(mac: String): String? = RadioDb.vendorForMac(mac)
}
