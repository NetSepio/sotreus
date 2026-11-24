/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit 60c8ecc).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed.
 */
package app.sotreus.intelligence.fieldwatch

/** Amazon Sidewalk rows. English stays here. Packs carry the other languages. */
internal object SidewalkLines {
    val all: List<EnglishLine> = listOf(
        en(
            "explain.sw.reg",
            "An Amazon Sidewalk device is advertising in registration mode.",
            LanguageSlot.BODY,
        ),
        en("sw.app", "Sidewalk, application 0x{id}", LanguageSlot.CONTROL),
        en("sw.battery", "Sidewalk battery", LanguageSlot.CONTROL),
        en("sw.battery.critical", "Critical", LanguageSlot.CONTROL),
        en("sw.battery.low", "Low", LanguageSlot.CONTROL),
        en("sw.battery.normal", "Normal", LanguageSlot.CONTROL),
        en("sw.battery.reserved", "Reserved", LanguageSlot.CONTROL),
        en("sw.beacon", "Sidewalk beacon", LanguageSlot.CONTROL),
        en("sw.chip", "Sidewalk registration", LanguageSlot.CONTROL),
        en("sw.mode.connect", "Connection request", LanguageSlot.CONTROL),
        en("sw.mode.normal", "Normal mode", LanguageSlot.CONTROL),
        en("sw.mode.registration", "Registration mode", LanguageSlot.CONTROL),
        en("sw.mode.reserved", "Reserved", LanguageSlot.CONTROL),
        en(
            "sw.note",
            "This is the Amazon Sidewalk BLE beacon, not a proof of the retail model.",
            LanguageSlot.BODY,
        ),
        en(
            "sw.note.reg",
            "Registration mode means the endpoint is offering itself to the Sidewalk network.",
            LanguageSlot.BODY,
        ),
        en("sw.protocol", "Protocol bit is not BLE", LanguageSlot.CONTROL),
        en("sw.type.connect", "Time-based connection request", LanguageSlot.CONTROL),
        en("sw.type.presence", "Presence / device state", LanguageSlot.CONTROL),
        en("sw.type.registration", "Registration / provisioning", LanguageSlot.CONTROL),
        en("sw.type.timesync", "Counter-based time sync", LanguageSlot.CONTROL),
    )
}
