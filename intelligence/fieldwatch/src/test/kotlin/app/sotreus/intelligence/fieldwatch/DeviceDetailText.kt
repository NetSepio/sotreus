/*
 * Adapted from Fieldwatch DeviceDetailText (https://github.com/OffGridPete/Fieldwatch, commit 60c8ecc).
 * Copyright (c) 2026 Off Grid Pete LLC. MIT License; see licenses/MIT-Fieldwatch.txt.
 * Modified for Sotreus: test fixture only, so upstream decoder tests run unchanged. Keeps the decoded
 * sections; the session, place, grid and vendor sections are left out.
 */
package app.sotreus.intelligence.fieldwatch

internal object DeviceDetailText {
    fun build(
        device: Sighting,
        signatureNames: List<String>,
        @Suppress("UNUSED_PARAMETER") now: Long = 0L,
        fleets: List<Fleet> = emptyList(),
        demo: Boolean = false,
    ): String {
        val facts = device.facts
        val guess = DeviceExplain.guess(device, signatureNames)
        val out = StringBuilder()

        fun line(label: String, value: String) {
            out.append(label).append(": ").append(value.trim()).append('\n')
        }
        fun section(title: String) {
            out.append('\n').append("## ").append(title).append('\n')
        }

        out.append(device.listTitle(signatureNames)).append('\n')
        line("MAC", if (demo) MacUtil.screenMac(device.mac, true) else device.mac)
        out.append(guess.headline).append('\n').append(guess.because).append('\n')

        if (device.channel != 0 || device.frequencyMhz != 0) {
            line("Channel / frequency", "channel ${device.channel}  ·  ${device.frequencyMhz} MHz")
        }
        if (device.kind == RadioKind.WIFI) {
            facts.security?.let { line("Security", DeviceExplain.wifiSecurityExplain(it)) }
            facts.interworking?.lines(demo)?.takeIf { it.isNotEmpty() }?.let { rows ->
                section("What the access point says about itself")
                out.append("The access point sets these. They are not checked.\n")
                rows.forEach { (label, value) -> line(label, value) }
            }
        }
        device.payloadAircraft?.trim()?.takeIf { it.isNotEmpty() }?.let { line("Aircraft", it) }
        device.payloadAuth?.let { auth ->
            section(LanguageText.text("rid.auth.type"))
            out.append(auth.typeName()).append('\n')
            line(LanguageText.text("rid.auth.page"), auth.pageValue())
            auth.timeText()?.let { line(LanguageText.text("rid.auth.time"), it) }
            auth.dataLine()?.let { out.append(it).append('\n') }
            auth.pastLine()?.let { out.append(it).append('\n') }
            auth.note()?.let { out.append(it).append('\n') }
        }
        val french = FrenchRid.fromFacts(facts)
        if (french.present) {
            section(LanguageText.text("rid.french.plate"))
            french.lines(demo).forEach { (label, value) -> line(label, value) }
            french.note()?.let { out.append(it).append('\n') }
        }
        facts.homekit?.let { hk ->
            section("HomeKit")
            hk.rows(demo).forEach { (label, value) -> line(label, value) }
            hk.setupLine()?.let { out.append(it).append('\n') }
            out.append(hk.note()).append('\n')
        }
        facts.matter?.let { matter ->
            section(LanguageText.text("matter.mode"))
            out.append(matter.headline()).append('\n')
            matter.rows(demo).forEach { (label, value) -> line(label, value) }
            matter.flagLines().forEach { out.append(it).append('\n') }
            matter.note()?.let { out.append(it).append('\n') }
        }
        facts.sidewalk?.let { sidewalk ->
            section(LanguageText.text("sw.beacon"))
            out.append(sidewalk.headline()).append('\n')
            out.append(sidewalk.modeLine()).append('\n')
            sidewalk.batteryRow().let { (label, value) -> line(label, value) }
            sidewalk.protocolLine()?.let { out.append(it).append('\n') }
            out.append(sidewalk.note()).append('\n')
            sidewalk.registrationNote()?.let { out.append(it).append('\n') }
        }
        facts.bthome?.let { bthome ->
            section("BTHome")
            bthome.versionRow().let { (label, value) -> line(label, value) }
            bthome.triggerLine()?.let { out.append(it).append('\n') }
            bthome.encryptedLine()?.let { out.append(it).append('\n') }
            bthome.objectRows(demo).forEach { (label, value) -> line(label, value) }
            bthome.commandLines().forEach { out.append(it).append('\n') }
            out.append(bthome.note()).append('\n')
        }
        facts.serviceData.forEach { sd ->
            ExposureNotification.label(sd)?.let { label ->
                section(LanguageText.text("en.label"))
                label.rows.forEach { (name, value) -> line(name, value) }
                label.fallback?.let { out.append(it).append('\n') }
                out.append(LanguageText.text("en.note")).append('\n')
            }
        }
        if (PayloadLocation.validCoord(device.payloadLat, device.payloadLon)) {
            line("Aircraft position", Geo.screenCoord(device.payloadLat, device.payloadLon, demo)!!)
        }
        if (PayloadLocation.validCoord(device.payloadOpLat, device.payloadOpLon)) {
            line(french.operatorLabel(device.payloadOpKind), Geo.screenCoord(device.payloadOpLat, device.payloadOpLon, demo)!!)
        }
        if (fleets.isNotEmpty()) {
            SignatureFieldDecoder.decodeSighting(device, fleets).forEach { row ->
                line(row.label, row.display)
                if (row.note.isNotBlank()) line("Note", row.note)
            }
        }
        device.serviceUuids.forEach { uuid -> line("Service", DeviceExplain.uuidGloss(uuid)?.let { "$uuid  ·  $it" } ?: uuid) }
        facts.serviceData.forEach { sd ->
            if (!ExposureNotification.matches(sd.uuid)) {
                AdvPayloadDecoder.decodeService(sd).forEach { field -> line(field.label, field.value) }
            }
            if (SmartTag.isService(sd.uuid)) {
                SmartTag.parse(sd.dataHex)?.let { tag ->
                    tag.state?.let { line("State", it) }
                    line("Aging", tag.aging.toString())
                    line("Battery", tag.battery)
                    line("UWB", tag.uwb)
                    line("Privacy ID", (if (demo) "masked" else tag.privacyId.hexSpaced()) + "\nNot a serial number.")
                }
            }
            val named = RadioDb.serviceUuid(sd.uuid)?.let { " ($it)" } ?: ""
            line("Service data " + uuidShort(sd.uuid) + named, if (demo && sd.dataHex.isNotBlank()) maskedRawBytes(sd.dataHex, true) else sd.dataHex.hexSpaced().ifBlank { "empty" })
        }
        val mfg = facts.mfgRecords.ifEmpty {
            device.manufacturerId?.let { listOf(MfgRecord(it, device.manufacturerDataHex)) } ?: emptyList()
        }
        mfg.forEach { rec ->
            AdvPayloadDecoder.decodeManufacturer(rec).forEach { field -> line(field.label, field.value) }
            if (rec.dataHex.isNotBlank()) {
                line("Raw payload (${rec.dataHex.length / 2} bytes)", if (demo) maskedRawBytes(rec.dataHex, false) else rec.dataHex.hexSpaced())
            }
        }
        facts.vendorIes.forEach { ie ->
            val org = RadioDb.vendorForOui24(ie.oui)
            line(
                "Vendor OUI ${ie.oui}" + if (ie.type >= 0) " type ${ie.type}" else "",
                VendorIeLabel.lead(ie, org, "Unknown IEEE OUI", "extra AP information element") +
                    if (ie.dataHex.isNotBlank()) " " + (if (demo) maskedRawBytes(ie.dataHex, true) else ie.dataHex.hexSpaced()) else "",
            )
        }
        if (device.fleetIds.isNotEmpty()) line("Matched signatures", signatureNames.joinToString("; ").ifBlank { device.fleetIds.joinToString("; ") })
        if (device.rawHex.isNotBlank() && device.kind == RadioKind.BLE) {
            line("Raw advertisement", if (demo) maskedRawBytes(device.rawHex, true) else device.rawHex.hexSpaced())
        }
        return out.toString().trimEnd() + "\n"
    }

    private fun uuidShort(uuid: String): String {
        val hex = uuid.filter { it.isLetterOrDigit() }.uppercase()
        return if (hex.length >= 8 && hex.startsWith("0000")) hex.substring(4, 8) else uuid.take(8)
    }
}
