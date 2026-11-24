/*
 * Ported from Fieldwatch (https://github.com/OffGridPete/Fieldwatch, commit 60c8ecc).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: package renamed.
 */
package app.sotreus.intelligence.fieldwatch

/** BTHome rows. English stays here. Packs carry the other languages. */
internal object BthomeLines {
    val all: List<EnglishLine> = listOf(
        en("bth.version", "BTHome version", LanguageSlot.CONTROL),
        en("bth.encrypted", "BTHome payload encrypted", LanguageSlot.CONTROL),
        en("bth.trigger", "Trigger-based advertisements", LanguageSlot.CONTROL),
        en(
            "bth.note",
            "BTHome v2 service data. Values are advertised by the device.",
            LanguageSlot.BODY,
        ),
        en(
            "explain.bth.door",
            "A BTHome door or window sensor is advertising open.",
            LanguageSlot.BODY,
        ),
        en(
            "explain.bth.motion",
            "A BTHome motion sensor is advertising motion.",
            LanguageSlot.BODY,
        ),
    )
}
