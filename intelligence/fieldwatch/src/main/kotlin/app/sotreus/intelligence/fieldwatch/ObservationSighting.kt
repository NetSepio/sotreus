package app.sotreus.intelligence.fieldwatch

/** A packet-only decoder view; it never claims address continuity or a physical identity. */
fun observationSighting(o: Observation) = Sighting(
    key = "${o.kind}:${o.mac}", kind = o.kind, mac = o.mac, name = o.name,
    rssi = o.rssi, rssiMin = o.rssi, rssiMax = o.rssi, channel = o.channel,
    frequencyMhz = o.frequencyMhz, vendor = null, randomized = true, hiddenSsid = o.hiddenSsid,
    serviceUuids = o.serviceUuids, manufacturerId = o.manufacturerId,
    manufacturerDataHex = o.manufacturerDataHex, rawHex = o.rawHex, extras = o.extras,
    firstSeen = o.at, lastSeen = o.at, hitCount = 1, fleetIds = emptySet(),
    rssiHistory = emptyList(), presence = emptyList(), facts = o.facts, vendorIeOuis = o.vendorIeOuis,
)
