package app.sotreus.intelligence.fieldwatch

/** English that Sotreus adds to the ported tables. */
internal object SotreusLines {
    val all: List<EnglishLine> = listOf(
        en("sotreus.wifi.elements", "Wi-Fi elements", LanguageSlot.CONTROL),
        en("sotreus.rid.auth.value", "{type}, not checked", LanguageSlot.BODY),
        en("sotreus.smarttag.state", "SmartTag", LanguageSlot.CONTROL),
        en("sotreus.rid.group", "{n} Remote ID aircraft first heard within {s} seconds", LanguageSlot.BODY),
    )
}
