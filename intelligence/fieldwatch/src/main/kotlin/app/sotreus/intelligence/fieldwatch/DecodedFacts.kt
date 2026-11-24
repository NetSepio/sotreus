/*
 * Adapted from Fieldwatch DeviceDetailText (https://github.com/OffGridPete/Fieldwatch, commit 60c8ecc).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: rows only, without the notes; only the structured facts.
 */
package app.sotreus.intelligence.fieldwatch

/** One thing a radio's advertisement says, for its detail page. */
data class FactRow(val label: String, val value: String)

/**
 * What the structured advertisement says: Wi-Fi Interworking and known elements, Remote ID
 * authentication and the French plate, SmartTag, HomeKit, Matter, Amazon Sidewalk, BTHome and
 * Exposure Notification. [masked] hides identifiers. Signature decode fields are separate
 * ([SignatureFieldDecoder]).
 */
object DecodedFacts {
    fun rows(device: Sighting, masked: Boolean): List<FactRow> {
        val facts = device.facts
        val out = ArrayList<FactRow>(8)
        fun add(rows: List<Pair<String, String>>) = rows.forEach { (label, value) -> out += FactRow(label, value) }
        facts.interworking?.let { add(it.lines(masked)) }
        wifiElements(facts)?.let { out += FactRow(LanguageText.text("sotreus.wifi.elements"), it) }
        device.payloadAuth?.let { auth ->
            out += FactRow(
                LanguageText.text("rid.auth.type"),
                if (auth.type == 0) auth.typeName() else LanguageText.text("sotreus.rid.auth.value", mapOf("type" to auth.typeName())),
            )
        }
        add(FrenchRid.fromFacts(facts).lines(masked))
        SmartTag.read(device)?.let { tag ->
            tag.state?.let { out += FactRow(LanguageText.text("sotreus.smarttag.state"), it) }
            out += FactRow(LanguageText.text("device.battery"), tag.battery)
            out += FactRow("UWB", tag.uwb)
        }
        facts.homekit?.let { hk ->
            out += FactRow(LanguageText.text("hk.category"), hk.categoryName)
            out += FactRow(LanguageText.text("hk.paired"), LanguageText.text(if (hk.paired) "hk.paired.yes" else "hk.paired.no"))
            out += FactRow(LanguageText.text("hk.deviceid"), MacUtil.screenMac(hk.deviceId, masked))
        }
        facts.matter?.let { matter ->
            out += FactRow(LanguageText.text("matter.mode"), matter.headline())
            add(matter.rows(masked))
        }
        facts.sidewalk?.let { sidewalk ->
            out += FactRow(LanguageText.text("sw.beacon"), sidewalk.headline())
            add(listOf(sidewalk.batteryRow()))
        }
        facts.bthome?.let { bthome ->
            bthome.encryptedLine()?.let { out += FactRow("BTHome", it) }
            add(bthome.objectRows(masked))
        }
        facts.serviceData.firstNotNullOfOrNull { ExposureNotification.label(it) }?.let { label ->
            if (label.rows.isEmpty()) label.fallback?.let { out += FactRow(LanguageText.text("en.label"), it) } else add(label.rows)
        }
        return out.distinct()
    }

    /** Names of published Wi-Fi elements in the beacon (WPS, Wi-Fi Direct, EasyMesh…). */
    private fun wifiElements(facts: RadioFacts): String? =
        facts.vendorIes.mapNotNull { VendorIeLabel.element(it)?.substringBefore(". ") }
            .distinct().joinToString(", ").ifBlank { null }
}
