package app.sotreus.core.data.edge

import app.sotreus.core.model.RadioKind
import app.sotreus.edge.protocol.BleSighting
import app.sotreus.edge.protocol.Hex
import app.sotreus.edge.protocol.Mac
import app.sotreus.edge.protocol.SightingBody
import app.sotreus.edge.protocol.SightingKind
import app.sotreus.edge.protocol.WifiApSighting
import app.sotreus.edge.protocol.WifiAuth
import app.sotreus.edge.protocol.WifiProbeSighting
import app.sotreus.intelligence.Fingerprint
import app.sotreus.intelligence.fieldwatch.Observation
import app.sotreus.intelligence.fieldwatch.RadioFacts
import app.sotreus.intelligence.fieldwatch.VendorIeRecord
import app.sotreus.sensing.radio.BleAdParser
import app.sotreus.intelligence.fieldwatch.RadioKind as FwKind

/**
 * Turns what the Edge heard into the same Fieldwatch [Observation] the phone's radios produce, so
 * classification, bands, attention and sessions work unchanged. The raw AD bytes go through the
 * phone's own parser; the Edge does not classify (protocol §4).
 */
object EdgeObservations {
    /** Marks probe-request clients so they are not mixed with access points. */
    const val PROBE_EXTRAS = "edge:probe"

    fun of(body: SightingBody, atMs: Long, rssi: Int = body.rssiLast): Observation = when (body) {
        is BleSighting -> ble(body, atMs, rssi)
        is WifiApSighting -> ap(body, atMs, rssi)
        is WifiProbeSighting -> probe(body, atMs, rssi)
    }

    fun key(body: SightingBody): String = Fingerprint.key(if (body is BleSighting) RadioKind.BLE else RadioKind.WIFI, body.address)

    fun key(kind: SightingKind, address: String): String = Fingerprint.key(if (kind == SightingKind.BLE) RadioKind.BLE else RadioKind.WIFI, address)

    /** A body for a live row whose `seen` body was missed: address and signal only. */
    fun bare(kind: SightingKind, address: String, rssi: Int, rssiMax: Int): SightingBody = when (kind) {
        SightingKind.BLE -> BleSighting(address, if (Mac.isLocallyAdministered(address)) 1 else 0, 0, rssi, rssiMax, rssi, false, false, 0, "", "")
        SightingKind.WIFI_AP -> WifiApSighting(address, 0, rssi, rssiMax, rssi, WifiAuth.UNKNOWN, false, false, false, true, 0, 0, "", "")
        SightingKind.WIFI_PROBE -> WifiProbeSighting(address, 0, rssi, rssiMax, rssi, Mac.isLocallyAdministered(address), false, false, false, 0, emptyList(), emptyList())
    }

    private fun ble(b: BleSighting, at: Long, rssi: Int): Observation {
        val adv = BleAdParser.parse(Hex.decode(b.advHex))
        val rsp = BleAdParser.parse(Hex.decode(b.scanResponseHex))
        val name = sequenceOf(adv.localName, rsp.localName).mapNotNull { it?.trim()?.trim('"')?.ifBlank { null } }.firstOrNull().orEmpty()
        val serviceData = adv.serviceData + rsp.serviceData
        val uuids = (adv.uuids + rsp.uuids + serviceData.map { it.uuid }).map { it.uppercase() }.distinct()
        val mfg = adv.mfg.ifEmpty { rsp.mfg }
        val facts = RadioFacts(
            txPowerDbm = adv.txPower ?: rsp.txPower,
            advFlags = adv.flags,
            appearance = adv.appearance ?: rsp.appearance,
            addressType = if (b.randomAddress) "Random" else "Public",
            advertisingIntervalMs = adv.advertisingIntervalMs,
            connectable = b.connectable,
            deviceClass = adv.deviceClass ?: rsp.deviceClass,
            mfgRecords = mfg,
            serviceData = serviceData,
        )
        return Observation(
            kind = FwKind.BLE,
            mac = b.address,
            name = name,
            rssi = rssi,
            channel = 0,
            frequencyMhz = 2402,
            hiddenSsid = false,
            serviceUuids = uuids,
            manufacturerId = mfg.firstOrNull()?.companyId,
            manufacturerDataHex = mfg.firstOrNull()?.dataHex.orEmpty(),
            rawHex = (b.advHex + b.scanResponseHex).take(1024),
            extras = "",
            at = at,
            facts = facts,
        )
    }

    private fun ap(a: WifiApSighting, at: Long, rssi: Int): Observation {
        val rid = Hex.decode(a.remoteIdHex)
        // Remote ID vendor element from the OUI onward: FA 0B BC <type> <data>.
        val ridIe = rid?.takeIf { it.size >= 4 }?.let { VendorIeRecord("FA:0B:BC", it[3].toInt() and 0xFF, Hex.encode(it.copyOfRange(4, it.size))) }
        return Observation(
            kind = FwKind.WIFI,
            mac = a.address,
            name = a.ssid,
            rssi = rssi,
            channel = a.channel,
            frequencyMhz = frequency(a.channel),
            hiddenSsid = a.hidden || a.ssid.isEmpty(),
            serviceUuids = emptyList(),
            manufacturerId = null,
            manufacturerDataHex = "",
            rawHex = a.remoteIdHex,
            extras = "",
            at = at,
            fresh = true,
            vendorIeOuis = listOfNotNull(ridIe?.oui),
            facts = RadioFacts(capabilities = capabilities(a.auth), security = security(a.auth), vendorIes = listOfNotNull(ridIe)),
        )
    }

    private fun probe(p: WifiProbeSighting, at: Long, rssi: Int): Observation = Observation(
        kind = FwKind.WIFI,
        mac = p.address,
        name = "",
        rssi = rssi,
        channel = p.channel,
        frequencyMhz = frequency(p.channel),
        hiddenSsid = false,
        serviceUuids = emptyList(),
        manufacturerId = null,
        manufacturerDataHex = "",
        rawHex = "",
        extras = PROBE_EXTRAS,
        at = at,
        fresh = true,
        facts = RadioFacts(capabilities = "probe request"),
    )

    fun frequency(channel: Int): Int = when (channel) {
        in 1..13 -> 2407 + 5 * channel
        14 -> 2484
        else -> 0
    }

    /** Android-style capability strings, so catalog rules written for the phone keep working. */
    private fun capabilities(auth: WifiAuth): String? = when (auth) {
        WifiAuth.OPEN -> "[ESS]"
        WifiAuth.WEP -> "[WEP][ESS]"
        WifiAuth.WPA -> "[WPA-PSK-TKIP][ESS]"
        WifiAuth.WPA2 -> "[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"
        WifiAuth.WPA3 -> "[RSN-SAE-CCMP][ESS]"
        WifiAuth.WPA2_WPA3 -> "[RSN-PSK+SAE-CCMP][ESS]"
        WifiAuth.ENTERPRISE -> "[WPA2-EAP-CCMP][RSN-EAP-CCMP][ESS]"
        WifiAuth.OWE -> "[RSN-OWE-CCMP][ESS]"
        WifiAuth.UNKNOWN -> null
    }

    private fun security(auth: WifiAuth): String? = when (auth) {
        WifiAuth.OPEN -> "Open"
        WifiAuth.WEP -> "WEP"
        WifiAuth.WPA -> "WPA"
        WifiAuth.WPA2 -> "WPA2"
        WifiAuth.WPA3 -> "WPA3"
        WifiAuth.WPA2_WPA3 -> "WPA2/WPA3"
        WifiAuth.ENTERPRISE -> "Enterprise"
        WifiAuth.OWE -> "Enhanced Open"
        WifiAuth.UNKNOWN -> null
    }
}
